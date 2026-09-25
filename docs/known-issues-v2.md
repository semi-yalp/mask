# arch-v2 已知问题与待确认项（v2 发布时点）

> 记录规则：本次重构**未实现/未修完/需要用户确认**的事项。每项含现状、影响与建议路径。

## A. 需要用户确认

| # | 事项 | 说明 |
|---|---|---|
| A1 | **主工作区未提交的监控指标代码** | main 工作区有一批未提交的 risk-server metrics 实施(2026-09-24)。arch-v2 已以自己的方式落地 query/risk 指标;原改动已存 stash@{0}(在 rr 工作区),恢复后建议甄别丢弃。rr(继承策略)已于 2026-09-25 适配新架构合入 main(67402da) |
| A2 | **旧部署数据迁移** | 旧两库(policy/metadata)表结构与单体同构但物理分离;合并脚本未提供(见 deployment.md §8),存量生产迁移需人工执行 |
| A3 | **改写失败放行(PASSTHROUGH)的启用策略** | 开关已实现但默认 REJECT;是否允许某些实例放行属业务决策,建议仅在可信分析师场景开启 |
| A4 | **远程主机验证范围** | 47.100.166.158 真机为旧版部署;本次未重装新架构,重装/迁移时间待定 |

## B. 未实现（设计已留）

| # | 事项 | 现状与建议 |
|---|---|---|
| B1 | 授权预检（网关执行前比对 grants） | 模型/编译/矩阵已落地;预检器未接。建议在 QueryService 改写判定后加 FROM 提取比对(可复用风控 SqlFeatureExtractor) |
| B2 | BlockService 进程内化 | 仍走 HTTP(单体默认自环 `http://127.0.0.1:8080`);**认证开启后自环会被 Bearer 门禁 401**,需要实现 mask-risk 的 BlockPolicyTarget 接口直连 PolicyService |
| B3 | 授权 apply 的 hive/sparksql/trino | 仅预览注释;各家授权模型(Ranger/Sentry/Trino access-control)需逐个接线 |
| B4 | UDF 发现/部署扩展到 trino/hive/sparksql | 仅 pg/mysql;trino 可查 system.metadata.functions,hive 需 metastore |
| B5 | 风控存储 PG 化 | 仍为内存窗+JSON 文件快照(demo 规模);多实例部署需 PG/ES store |
| B6 | InstanceRewriteConfig 的 HTTP 双传输在单体的自动化 | 本地传输为 @Primary;若指向远程独立服务需手工停用本地 bean(文档化,未做开关) |
| B7 | 授权/分类分级变更审计 | 两域写路径未接 AuditAdminHelper(策略/元数据域已接) |
| B8 | 授权组(GROUP)与认证组的打通 | simple 模式用户的 groups 即授权 GROUP 主体;LDAP 组自动同步未做 |

## C. 缺陷/低配（本次未修）

| # | 事项 | 现状 |
|---|---|---|
| C1 | 前端无响应式断点 | 旧版问题;本次未做 <1024px 折叠(优先级让位于新功能页) |
| C2 | risk 页面硬编码配色游离于设计令牌外 | 未动 |
| C3 | 前端 token/无 X-Api-Key 仍存 localStorage | API Key 输入框保留兼容旧部署;新部署可忽略 |
| C4 | Bench 全量 99×3 矩阵未在新 jar 重跑 | 资产已恢复、脚本需把"engine=lite/core"改为 sqlmask 单体 CLI;抽样回归待执行(见 T13 记录) |
| C5 | Remote PG 真机 e2e-verify-v2 未执行 | 脚本待写(指向单体 8080);远程重装后补跑 |
| C6 | `docs/` 内旧文档(功能清单.md/使用手册/端到端指南)未全部重写 | 以 功能清单-v2/deployment.md 为准,旧文档标注历史口径 |
| C7 | AuditQuery 的 resourceType/action 过滤(jdbc store)依赖精确列值 | ES 版为分词匹配;jdbc 版为等值,行为略有差异 |

## D. 本次重构明确做对的加固（对照旧问题报告）

- 已提交入库的合并冲突标记(nginx.Dockerfile/.gitignore)清零
- README 与代码不一致的 metadata fail-open 口径问题:API-Key 门禁整体退役,不再存在
- RewriteMetrics 方言标签白名单缺 hive/sparksql → 已修
- query/risk 监控盲区 → 单体统一 actuator/prometheus + 新增指标

## 追记(2026-09-25,rr 合并后)

- 复制表策略继承(inheritOnCopy)已随 rr 分支并入单体:注册器在 mask-server,钩子在 InstanceRewriteService(REST 与查询网关同路);本地 H2 全链路 E2E 通过(CTAS→auto.inherit.* 策略注册→结构登记→legacy 通道拒绝)。
- 继承功能暴露并修复三处适配问题(67402da):inheritedTables 类型声明归一(不再产出 bigint(19,0))、注册器 ObjectMapper 容错、structure PUT 适配 /api/meta 命名空间。
- B2 口径扩展:继承注册器的元数据/策略上游同样默认自环(可在 yml 覆盖),认证开启后与 BlockService 同样需要凭据或进程内化。
- 远程 47.100.166.158 sshd 无响应(疑似大 jar 传输诱发小内存主机僵死),需云控制台重启后重跑真机验证;9090 旁路进程部署的仍是合并前 jar。
