# 架构合理性再评估与演进方向（arch-v2）

> 2026-09-24 · 基于 baseline-inventory-20260924.md 的基线结论

## 1. 现有微服务拆分的合理性评估

**结论：内核抽取是成功的，服务拆分是过度设计。**

做对了的（保留）：
- mask-engine 纯内核（enforcer 禁 Spring）——"可嵌入查询引擎"的基础，正是本次要保留并强化的资产。
- mask-policy 纯模型库、mask-sqlparser 自研解析器、契约放 mask-common 的零互相依赖纪律。
- 改写 fail-closed 语义、行过滤白名单、UDF 签名严格校验等安全工程。

不合理处：
1. **拆分动机不成立**。5 个服务无一具备独立伸缩/独立可用性需求：策略与元数据是改写的必经输入（拆开只带来 30s 一致性窗口与两跳 HTTP）；查询服务依赖全部三者；风控单进程 demo 存储。拆分带来的是 4 个部署面 × 3 套 API Key × 2 套认证，与"本地可跑、重点发展功能"的目标直接冲突。
2. **改写热路径被拉长**：query→core→(policy,metadata) 三次网络往返 + LRU 缓存 + 轮询刷新。而改写本身是纯内存计算，正确形态是"编译快照 + 进程内原子切换"。
3. **认证模型错位**：API Key 是服务间认证，但服务间调用本不存在信任边界（同机房同compose）；用户要的是"默认不认证 + 可选对接 LDAP/IAM"。当前把两种都做重了。
4. **命名倒挂**：mask-core 是应用、mask-engine 是内核。

## 2. 目标架构：模块化单体 + 可嵌入内核（Modular Monolith + Embeddable Kernel）

```
┌────────────────────────── frontend (Vue3, 单源 /api) ──────────────────────────┐
└──────────────────────────────────────┬─────────────────────────────────────────┘
                                       │ HTTP (可选认证: none/simple/ldap)
┌──────────────────────────────────────▼─────────────────────────────────────────┐
│  mask-server（模块化单体, :8080, 唯一进程）                                        │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ ┌───────────┐  │
│  │ 元数据域  │ │ 策略域    │ │ 查询网关  │ │ 风控域    │ │ 审计域   │ │ 认证域     │  │
│  │ 实例/结构 │ │ 策略/UDF  │ │ Submitter│ │ 规则/告警 │ │ ES/JDBC │ │ none/simple│  │
│  │ 分类分级  │ │ 授权grant │ │ SPI 注册 │ │ UEBA/阻断 │ │ /Noop   │ │ /ldap     │  │
│  └────┬────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬────┘ └───────────┘  │
│       └───────────┴────────────┴────────────┴────────────┘                     │
│                     ▼ RewriteContextRepository（版本化编译快照，原子切换）          │
│  ┌──────────────────────────────────────────────────────────────────────────┐  │
│  │ mask-engine 内核（纯库，零 Spring，enforcer 强制）—— 可单独嵌入查询引擎        │  │
│  │ mask-sqlparser · mask-policy · 行过滤 · 血缘 · 五方言 · fail-closed         │  │
│  └──────────────────────────────────────────────────────────────────────────┘  │
│  存储：PostgreSQL（默认） / H2 文件（零配置本地） / ES（可选审计）                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

核心决策：
- **D1 单体合并**：mask-core/policy-server/metadata/query/risk-server 五应用并为一个 `mask-server`；原服务模块降级为"域库"（保留包结构与测试，只去 Application/端口/yml）。CLI 保留（同 jar 双模）。
- **D2 改写热路径进程内化**：`RewriteContextRepository` 把"元数据快照 + effective 编译产物"编译成不可变 RewriteContext，volatile 原子切换；元数据/策略任何变更 → 重编译。改写请求零 HTTP、零锁、零一致性窗口。**策略与元数据域的故障/变更不阻塞改写（旧快照继续服务）**，满足"元数据和策略不影响改写功能和性能"。
- **D3 认证可选化**：`mask-auth` 升级为多模式（none 默认 / simple 本地用户 / ldap / 预留 oidc-iam Provider 接口）。API Key 体系整体移除（进程内无服务间调用）。simple 模式：本地用户表 + PBKDF2 + 复用 JWT；首启建 admin 账号。
- **D4 查询网关轻量化**：`QuerySubmitter` SPI（jdbc 默认 / http 通用模板提交器），提交方式注册进系统、按实例选择。保留超时/并发/maxRows 护栏与改写不可绕过语义。
- **D5 改写失败放行开关**：内核保持 fail-closed 不动；放行逻辑在网关层 per-instance 配置 `onRewriteFailure: REJECT(默认)|PASSTHROUGH`，放行时审计标 rewriteBypassed、风控 MASK_BYPASS 规则盯防。
- **D6 UDF 对齐查询引擎**：UDF 中心 = 签名登记（现状）+ **引擎发现导入**（introspector 反射 pg_proc / mysql routines）+ **创建部署**（内置 plpgsql 模板直接 CREATE FUNCTION 到引擎；其他引擎走 DDL 模板确认执行）+ **动态同步**（PG 函数运行时可变的，提供 re-sync 比对差异）。策略校验以引擎侧真相为准。
- **D7 元数据层统一授权**：`grant` 模型（principal × 资源四级 × 权限）挂在元数据域；编译为各引擎 GRANT DDL（预览+可选执行），查询网关可选预检（默认关）。v1 不做引擎→本系统反向同步（记录为后续项）。
- **D8 分类分级**：元数据域新增列级 classification（类别×级别，内置启发式自动识别 + 手工修正），策略创建按分类选列，风控敏感列注册表可从 HIGH 级同步。
- **D9 审计存储分级**：JDBC(PG/H2) 默认 / ES 可选 / Noop；风控联动改进程内直投（去 HTTP RiskForwarder）。
- **D10 前端单源重构**：同源 /api、响应式、统一设计令牌、修复已知 bug（QueryConsole 禁用 bug、N+1、冲突标记）；页面按新域重组（新增分类分级、UDF 中心、授权页）。
- **D11 部署极简**：一个 docker compose（pg + server(+内嵌前端静态) + 可选 profile: es/prometheus/ldap）；server 可直接 `java -jar` 裸跑（H2 回退）。
- **D12 TPC-DS 回归**：从旧分支恢复 bench/ 全量资产，适配新 CLI；mask-engine golden 测试保留。

## 3. 演进方向清单（本次实施 / 后续 / 远期）

### 本次实施（arch-v2）
1. 单体合并 + RewriteContextRepository 热路径（D1/D2）
2. 认证 none/simple/ldap 多模式，默认 none（D3）
3. 查询网关 Submitter SPI + 改写失败放行开关（D4/D5）
4. UDF 中心：发现/部署/动态同步（D6）
5. 元数据统一授权 v1：模型+GRANT 编译+网关预检（D7）
6. 分类分级 + 前端（D8）
7. 审计 JDBC 存储 + 风控进程内嵌入（D9）
8. 前端重构（D10）
9. docker compose 一键部署 + 文档（D11）
10. TPC-DS bench 恢复（D12）
11. 全量回归（对 baseline-inventory 清单）+ 远程 PG 真机验证

### 后续版本（记录待确认/待做）
- OIDC/IAM 对接（AuthProvider 接口已留）；LDAP 组→授权主体映射联动
- 授权反向同步（引擎实际 grants 拉回对比漂移）
- 内核嵌入查询引擎的发行形态（-fat jar/ shaded / SPI 包装修剪）；Trino coordinator 插件化试点
- 新方言：doris 独立 profile、sqlserver（TOP 语法已备）、presto；UDF 发现扩展到 trino/hive
- 风控规则市场/自定义 SQL 特征规则；告警 webhook 模板化
- 多租户/命名空间；审计 ES ILM；Grafana 看板
- 前端 i18n、暗色模式、E2E（Playwright）
- 行过滤条件支持更多运算符（LIKE/BETWEEN 白名单扩展）
- CLI 的 --instance 模式去 HTTP 化（直连库）后 deprecated 策略服务地址参数

### 明确不做（防蔓延）
- 不做 SQL 执行代理协议层（JDBC 代理/网络中间件）
- 不做数据发现扫描（列内容采样识别分类）——分类分级 v1 只做元数据/名称启发式
- 不做策略的细粒度时间维度（有效期）与审批流

## 4. 风险与对策
| 风险 | 对策 |
|---|---|
| 单体合并破坏现有测试（各模块 @SpringBootTest 挂载各自 Application） | 各域库内建最小 TestApp 配置；上下文测试改为切片化 |
| JSONB→TEXT 迁移 | 全新 schema（版本化 init 脚本），旧库不自动迁移，文档说明 |
| 前端重构工作量失控 | 保留 Element Plus 与既有 API 层，重排布局/主题/新页面，不推倒重写 |
| fail-open 开关被误用 | 默认 REJECT；放行必须实例级显式配置 + 审计/风控标记 |
