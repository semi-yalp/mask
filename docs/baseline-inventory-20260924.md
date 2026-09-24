# sql-mask 现状功能清单与基线报告（重构前）

> 基线：origin/main @ `448c280`（2026-09-24），worktree `.worktrees/arch-v2`，分支 `feature/arch-v2`。
> 本文档是 arch-v2 重构的**功能对照基线**，重构完成后按此清单逐项回归。

## 1. 项目定位

基于 Apache Calcite 的多引擎 SQL 脱敏改写平台："只做解析/校验/血缘/改写，从不执行业务 SQL"（内核层），配套元数据、策略、受控查询、审计、风控、LDAP 认证与 Vue3 控制台。

## 2. 模块拓扑（12 个 Maven 模块）

| 模块 | 形态/端口 | 职责 | 规模（主/测试文件） |
|---|---|---|---|
| mask-sqlparser | 纯库 | 自研 Calcite 1.42 + babel 语法基底的解析器；INSERT OVERWRITE / TOP 产生式 | 2/7 |
| mask-policy | 纯库（零 Spring） | Ranger 式策略模型 + glob/主体匹配 + policies.yaml 读写 | 16/8 |
| mask-engine | 纯内核（enforcer 禁 Spring/picocli） | 方言/改写/血缘/行过滤/YAML 配置/introspect | 72/40 |
| mask-common | 共享库 | ApiKeyFilter、ApiError、MetadataClient、Effective 契约、指标 | 7/4 |
| mask-core | 应用 :8080 + CLI | 改写 REST、实例模式缓存（LRU256+30s 轮询）、CLI、审计查询面 | 19/30 |
| mask-policy-server | 应用 :8081 | 实例/策略/UDF 管理面、effective 编译数据面、LDAP 登录面 | 27/25 |
| mask-metadata | 应用 :8082 | 实例登记、结构采集(pg/mysql/trino)、YAML 导入、数据面快照 | 21/18 |
| mask-query | 应用 :8083 | 受控查询数据面（改写不可绕过；JDBC 直连六引擎） | 18/13 |
| mask-auth | 库 + Filter | HS256 JWT + LDAP 认证 + Bearer 角色门禁 | 9/4 |
| mask-audit | 库 + AutoConfiguration | 审计事件模型 + ES 异步写入 + 查询客户端 + 风控转发 | 18/11 |
| mask-risk-server | 应用 :8084 | 风控引擎、16 内置规则、告警、一键阻断、UEBA、Demo | 48/9 |
| mask-build-tools | 构建期 | shade 的 spring.factories 并集 transformer | 1/0 |

前端：Vue 3.5 + TS strict + Element Plus 2.8 + Pinia + vue-router + CodeMirror 6，nginx 反代网关（80）。后端 Java 17 / Spring Boot 3.3.5 / Calcite 1.42.0。总计 428 个 Java 文件（主 258/测试 169），前端 46 源文件 + 11 测试 spec。

## 3. 核心能力清单

### 3.1 SQL 改写内核（mask-engine，本项目的基础）
- [x] 五方言：postgresql / trino / mysql / hive / sparksql（大小写不敏感注册）；StarRocks 复用 mysql 方言（仅文档口径）；方言能力位（quoting/casing/conformance/搜索路径/STRICT 级别）声明式装配。
- [x] 改写方式：外层投影包裹——`SELECT udf(r.col) AS col FROM (原始SQL) AS r`；内层=校验前快照原文逐字节不动。
- [x] 语句种类：SELECT / WITH…SELECT / ORDER_BY 包裹查询 / INSERT…SELECT / INSERT OVERWRITE TABLE（hive/sparksql）/ CTAS；UPSERT 拒绝；纯 VALUES 写语句语义安全放行。
- [x] 血缘：Calcite RelMetadataQuery 逐输出列溯源；标量子查询 UNKNOWN→fail-closed 拒绝。
- [x] 行过滤：白名单条件（拒绝子查询/函数/CAST/聚合/动态参数/非本表列）注入为派生表；覆盖 JOIN/CTE/UNION 分支/写语句源查询；多命中 AND 合并；歧义引用拒绝。
- [x] 多来源列策略 tie-break：字典序最小 ColumnKey。
- [x] 多语句切分（PG 词法：dollar-quote/注释/引号）；非递归 CTE 内联。
- [x] 全线 fail-closed：PARSE/VALIDATION/LINEAGE_UNKNOWN/REWRITE/CONFIG 任一失败中止整跑，**无"失败放行"开关**（语义安全的无命中放行除外）。
- [x] 自研解析器扩展：`SqlInsertOverwrite`（拒绝 PARTITION/DIRECTORY）、`SqlMaskTopN`（TOP→fetch，拒绝 PERCENT/WITH TIES）——**TOP 开关当前无任何方言启用**。
- [x] UDF 名引用驱动：策略声明 `{udf, arguments}`，算法实现在引擎侧；UnknownFunctionTable 让未知函数过验证且血缘穿透。
- [x] introspect：pg/mysql/trino 只读 JDBC 元数据采集 → metadata.yaml；NetworkGuard SSRF 防护。
- [x] 测试：40 测试类/335 用例，含 TPC-DS 字节级 golden（pg/mysql/trino × 多变体）+ 与 SqlBabelParserImpl 的差分等价测试。

### 3.2 策略子系统
- [x] Ranger 式 policies.yaml：`Policy(name,enabled,priority,type,resources[],dataMaskItems[],rowFilterItems[])`；四级资源 glob；主体 users/groups，特异性 user(3)>group(2)>\*(1)。
- [x] PDP：`PolicyEngine.maskFor`（列级唯一命中决策）/`rowFiltersFor`（全部命中 AND）。
- [x] 管理面（policy-server）：实例/表结构/策略/UDF CRUD；策略校验（UDF 签名首参类型精确相等、重叠检测、行过滤白名单）；effective 编译（`EffectiveConfigResponse` 契约放 mask-common）。
- [x] 策略 YAML 整实例导入导出（按名 upsert，整文件预校验）。
- [x] 三策略来源互斥：legacy metadata.yaml 内嵌 / 独立 policies.yaml / 策略服务 instance 模式。
- [x] UDF 注册表：签名（params/returns，多重载）注册 + 写入校验。

### 3.3 元数据子系统（mask-metadata）
- [x] 实例登记（dialect/engine/host/port/database/dbUser/passwordRef 环境变量名/sslmode/schemas 过滤/includeViews）+ metadata_version 版本机制（同事务递增）。
- [x] 在线采集：pg/mysql/trino（hive/sparksql 显式拒绝）；EnvCredentialResolver（SQLMASK_ 前缀白名单）；类型经方言 TypeResolver 校验。
- [x] YAML 导入（只吃 metadata.tables；rowFilter 字段 400 拒绝）；数据面快照 API。
- [x] 存储：PG（meta_instance/meta_table/meta_column，JSONB 列）；无 PG 时测试用内存实现。

### 3.4 查询子系统（mask-query）
- [x] 唯一端点 `POST /api/v1/query`：metadata 拉连接 → core 改写（不可绕过）→ JDBC 直连执行；只读面（非 SELECT 拒绝、单语句）。
- [x] 六引擎执行目录：postgresql/mysql/starrocks/trino/hive/sparksql；engine-dialect 一致性校验。
- [x] 护栏：每实例并发信号量（QUERY_BUSY 429）、语句超时 + 容器级超时兜底 + CancelRegistry 取消、maxRows 硬上限 + 多读一行判定 truncated、只读连接/事务。
- [x] 匿名模式：user/groups 可缺省（仅 \* 策略命中）；Bearer 令牌主体覆盖自报主体。

### 3.5 认证与授权
- [x] API Key：X-Api-Key，常量时间比较，failClosed（query）/failOpen（其余）+ surfaces 分面（metadata 双面）。
- [x] LDAP + HS256 Bearer（8h）：角色 ADMIN>AUDITOR>USER 按组映射；数据面主体绑定；12 个 MASK_AUTH_\* 环境变量；OpenLDAP 演示目录 + smoke.sh。
- [x] 各服务 Bearer 路径×方法×角色门禁规则。

### 3.6 审计与指标
- [x] 四类事件（REWRITE/ADMIN_CHANGE/EFFECTIVE_PULL/QUERY）统一信封；ES 异步尽力写入（批量/滚动索引/截断/模板）；查询 API（7 天/200 条上限）。
- [x] 风控转发 RiskForwarder（HTTP 异步批量）。
- [x] Prometheus：8080/8082 暴露 /actuator/prometheus（rewrite/admin/effective/metadata/audit 指标）；**query/risk 无指标（监控盲区）**；RewriteMetrics 方言标签白名单缺 hive/sparksql。

### 3.7 风控（mask-risk-server）
- [x] 16 内置规则（SQLI 族 7 + 行为族 8 + 自定义 DSL 规则）；滑动窗口/首访基线/UEBA 基线；评分与告警分组（冷却窗/升级/状态机）。
- [x] 一键阻断（向 policy-server 下发 ROW_FILTER 1=0 + 验证）；敏感列注册表；统计大盘/UEBA 画像/通知（webhook）；11 攻击场景 Demo + 48h 种子故事。
- [x] 存储：内存滚动窗（5 万）+ JSON 文件持久化（demo 规模）。

### 3.8 前端控制台（15 路由）
- [x] Dashboard / 登录页 / 访问管理（方言分组实例卡片墙）/ 元数据服务管理 / 策略管理器（策略+表结构+UDF+生效配置 4 Tab，创建联想）/ 查询控制台（本地历史）/ 改写试验台 / 审计检索 / 风控 5 页（大盘 SVG 图表/告警/事件/规则/资产）/ 设置（API Key+拓扑）。
- [x] LDAP 登录态 + 角色显隐 + API Key 弹窗；11 个 vitest spec。
- [ ] 已知缺陷：QueryConsole 用列表接口缺 connection 字段导致实例全被禁用（疑似 bug）；MetadataManager N+1 请求；无响应式（@media 零命中）；risk 配色/时间格式两套体系；`frontend/.gitignore` 与 `docker/nginx.Dockerfile` 存在**已提交的合并冲突标记**；路由无角色 meta（仅菜单显隐）。

### 3.9 CLI 与基准
- [x] CLI（sql-mask.jar，picocli）：inline/instance 模式改写、--pull-metadata 采集、--strict 导出。
- [x] TPC-DS：mask-engine 内 12 个手工语料 + golden 字节级测试（99 条全量基准在旧分支 bench/，**未在 main**；曾达成 lite 99/99 全过、core 95/99）。
- [x] CI：mvn verify（含 enforcer 内核纯度、嵌入式 PG E2E）+ 前端 test/build。

### 3.10 部署资产
- [x] 5 个独立 compose（policy/metadata/query/metrics/frontend）+ OpenLDAP compose；原生 systemd 部署脚本（2C/1.6G 调优）；远程 ECS（47.100.166.158）原生部署实录 + e2e 脚本。
- [ ] 无全家桶一键 compose。

## 4. 服务间调用与已知架构问题

调用链：query→(metadata,core)；core→(policy-server,metadata)；policy-server→metadata（导入时）；risk→policy-server（阻断）；audit→ES + risk（HTTP 转发）。无注册中心，HttpClient 直连。

| # | 问题 | 影响 |
|---|---|---|
| A1 | mask-core/mask-engine 命名与职责倒挂（core 是应用，engine 才是内核） | 认知负担 |
| A2 | 改写热路径跨进程：core 实例模式需 HTTP 拉 effective+metadata（LRU+30s 轮询+stale-but-available） | 延迟与一致性窗口、运维复杂 |
| A3 | 5 服务 4 库面 + 3 套 API Key + 双认证体系，本地体验差 | 用户核心痛点 |
| A4 | README 与代码不一致：metadata surfaces 实际 fail-open | 文档可信度 |
| A5 | query/risk 无 actuator 指标；RewriteMetrics 方言白名单落后 | 监控盲区 |
| A6 | risk-server 48 主类仅 9 测试类 | 覆盖薄弱 |
| A7 | nginx.Dockerfile/.gitignore 带已提交冲突标记 | 仓库卫生 |
| A8 | 无分类分级能力；UDF 只有签名登记，不能发现/部署引擎 UDF；无统一授权 | 功能缺口（本次重构目标） |
| A9 | 改写失败无放行开关；查询提交方式仅 JDBC 直连 | 功能缺口（本次重构目标） |
| A10 | 主工作区有未提交监控指标实施代码（risk metrics/query 匿名测试） | 待并入或废弃 |

## 5. 主工作区未提交内容（不在 main，重构时注意）

`docs/monitoring-metrics-plan.md`（234 行指标方案 v1.0）+ mask-risk-server `metrics/` 包与 RiskMetricsTest + mask-query QueryAnonymousModeTest/Application 修改 + AlertManager/RiskEngine/IngestController/AlertController/application.yml/pom.xml 修改 —— 即 monitoring-metrics 方案已动工未提交。**处置：arch-v2 重构中将指标能力（query/risk 的 Micrometer）纳入设计一并实现，主工作区改动不合并、留在原处由用户决定去留。**
