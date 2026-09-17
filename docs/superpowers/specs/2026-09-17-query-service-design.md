# 统一查询服务设计方案（mask-query 模块 + 按实例改写 + 四引擎执行）

日期：2026-09-17
状态：已经评审通过（brainstorming 五节逐节确认）

## 1. 背景与目标

本仓库现有能力止步于「改写」：mask-core 接受原始 SQL，输出带脱敏 UDF
包装的 SQL，从不执行业务 SQL。真实执行一直由使用方自行完成。

本设计新增**统一查询服务（mask-query）**，把链路走完：调用方提交
「原始 SQL + 目标实例 + 查询主体」，服务内部自动完成
**拉取配置 → 改写 → JDBC 执行 → 返回脱敏后结果集**。改写环节不可绕过，
调用方接触不到未脱敏数据。

已确认的关键决策：

| 决策点 | 结论 |
|---|---|
| 查询系统 | 六种引擎：MySQL、PostgreSQL、Hive、Spark/SparkSQL、Trino、StarRocks |
| 交互模式 | 统一查询 API（改写在服务内部强制发生） |
| 列脱敏层 | **引擎内 UDF**：改写产物直接可执行，各引擎侧预装脱敏函数 |
| 方言覆盖 | 分两批：批 1 = PG / MySQL / Trino 原生 + StarRocks（复用 MySQL 方言）；批 2 = 新增 hive / sparksql 方言 profile |
| 执行模型 | 同步 + 硬限制（超时 / 最大行数 / 断连取消）；异步任务模型为后续演进 |

引擎侧 UDF 安装是**部署前提**，不是本服务职责：PG / MySQL 建函数，
Hive / Spark 传 JAR 注册，StarRocks 3.x Java UDF，Trino 写 Java 插件。

## 2. 范围与非目标

### 范围（本 spec = 批 1）

1. 新建 Maven 模块 `mask-query`（加入父 pom，缺省端口 8083）；
2. mask-core 新增「按实例改写」端点与响应 `kind` 字段；
3. mask-metadata 实例模型新增 `engine` 概念并支持 `starrocks`；
4. mask-query：鉴权、护栏、四引擎 JDBC 执行、结果裁剪、QUERY 审计。

### 非目标（显式不做）

- 异步任务模型（queryId / 轮询 / 取消 API）；
- 连接池（一期每次查询新建连接）；
- Hive / SparkSQL 方言与执行器（批 2 另立 spec）；
- 结果导出格式（CSV / Arrow），一期仅 JSON；
- mask-core 全局鉴权改造（仅给新端点加可选 key）；
- 跨引擎转写（输入与输出同为该实例方言，与既有口径一致）；
- 调用方身份验证体系（`user`/`groups` 是上游声明值，同现状）。

## 3. 架构总览

数据面与控制面分离（方案 A）：

```
调用方 ──HTTP──> mask-query(8083, 数据面)
                   │  1. GET 实例连接信息
                   │───────> mask-metadata(8082)
                   │  2. POST 按实例改写（内部拉取元数据快照 + 按主体生效配置）
                   │───────> mask-core(8080) ──> mask-metadata / mask-policy
                   │  3. JDBC 执行改写产物（PG / MySQL / Trino / StarRocks）
                   └─ 4. 裁剪结果集 → 返回 JSON；异步记 QUERY 审计事件（mask-audit → ES）
```

- mask-query 不存任何实例/策略状态，全部现拉；自身无数据库；
- 改写编排留在 mask-core（`MetadataClient`、`PolicyServiceConfigSource`
  按主体 LRU 缓存等客户端都是现成的），`REWRITE` 审计事件继续由
  mask-core 发射，不迁移；
- mask-query 只新增「执行」这一件事：连接、护栏、结果集、QUERY 审计。

## 4. API 契约（mask-query）

### POST /api/v1/query

```json
// 请求
{
  "instance": "pg_prod",        // 必填，mask-metadata 实例名
  "sql": "SELECT phone FROM customer",   // 必填，仅单条语句
  "user": "alice",              // 可选，查询主体
  "groups": ["devs", "ops"],    // 可选
  "maxRows": 1000,              // 可选，缺省 query.max-rows（默认 1000）
  "includeRewrittenSql": false  // 可选，true 时响应回显改写后 SQL（调试用）
}
```

成功 200：

```json
{
  "instance": "pg_prod",
  "engine": "postgresql",
  "columns": [ { "name": "phone", "type": "varchar" } ],
  "rows": [ ["138****1234"], ["139****5678"] ],
  "rowCount": 2,
  "truncated": false,
  "masked": true,
  "rowFiltered": false,
  "elapsedMs": 123
}
```

- `rows` 是按列位置对齐的数组的数组（列名可能重复/大小写敏感，用位置对齐）；
- `truncated: true` 表示命中行数上限被截断（不是错误）；
- `masked` / `rowFiltered` 取自改写结果，如实标注这条语句发生了什么；
- `columns` 的 `type` 是 JDBC 元数据返回的列类型名，仅作展示。

### 契约要点

- **单语句**：多语句（改写结果多于一条，或输入含分号分隔的多条）直接拒绝，
  错误码 `MULTI_STATEMENT`——多结果集让契约与护栏复杂化，一期不做；
- **数据面只读**：只允许 `SELECT` / `WITH ... SELECT`。改写器本身接受
  `INSERT ... SELECT` / `CTAS`，由 mask-core 改写响应新增的 `kind` 字段
  区分，mask-query 对非 `SELECT` 拒绝，错误码 `WRITE_STATEMENT`；
- `maxRows` 高于服务端硬上限（`query.max-rows-hard`，默认 10000）时
  **钳制到硬上限执行**，不报错，`truncated` 如实标注。

### 错误契约

沿用全系统惯例 `{code, message}`，HTTP 400（鉴权失败 401）：

| 来源 | 错误码 |
|---|---|
| mask-query 自有 | `MULTI_STATEMENT`、`WRITE_STATEMENT`、`INSTANCE_NOT_FOUND`、`INSTANCE_NOT_EXECUTABLE`（实例无连接信息，YAML 导入的实例不可执行）、`QUERY_BUSY`（该实例并发已达上限，快速失败不排队）、`QUERY_TIMEOUT`、`QUERY_ERROR`（引擎执行失败，message 带 SQLState 与引擎原始消息，不含凭据）、`REWRITE_SERVICE_UNAVAILABLE`（mask-core 不可达或返回不可用响应，fail closed）、`CREDENTIAL_UNAVAILABLE`（passwordRef 指向的环境变量未设置）、`UNSUPPORTED_ENGINE`（实例 engine 超出批 1 引擎目录）、`CONFIG_ERROR`（请求参数问题） |
| 改写阶段透传 mask-core | `CONFIG_ERROR` / `PARSE_ERROR` / `VALIDATION_ERROR` / `UNSUPPORTED_STATEMENT` / `LINEAGE_UNKNOWN` / `REWRITE_ERROR` / `METADATA_INSTANCE_NOT_FOUND` / `METADATA_SERVICE_UNAVAILABLE` / `POLICY_SERVICE_UNAVAILABLE`（依赖不可达一律 fail closed） |

本表与实现同步于 2026-09-17 计划执行期（以 `mask-query` 的 `QueryException` 实现为准）。

## 5. mask-core：按实例改写端点

```
POST /api/rewrite/instances/{name}
body: { "sql": "...", "user": "alice", "groups": ["devs"] }
```

- 装配走现成客户端：`MetadataClient.fetch(name)` 取
  `MetadataSnapshot(dialect, tables)`；`PolicyServiceConfigSource.load(subject)`
  取按主体编译的生效配置；随后进入既有解析 → 校验 → 血缘 → 改写管线；
- **响应在 `statements[]` 每条增加 `kind` 字段**：
  `SELECT` / `INSERT_SELECT` / `CTAS`（原样无包装的语句同样是 SELECT kind；
  判定来自解析期语句类型，血缘/包装结果不影响它）；
- 要求 mask-core 处于服务模式（配置了 metadata/policy 服务地址）；未配置时
  返回 `CONFIG_ERROR`。内联 YAML 的 `POST /api/rewrite` 行为逐字节不变；
- 实例不存在透传 `METADATA_INSTANCE_NOT_FOUND`；
- `REWRITE` 审计事件照旧由 mask-core 发射，携带 instance 字段；
- 鉴权：可选 `X-Api-Key`（环境变量 `SQLMASK_REWRITE_API_KEY`，未配置放行，
  向后兼容）；mask-query → mask-core 的服务间调用携带它。

## 6. mask-metadata：实例模型扩展

现状：`InstanceRow(name, dialect, connection, metadataVersion)`，
`dialect ∈ {postgresql, trino, mysql}`，`connection` 可整体缺省
（YAML 导入的实例）。

扩展（最小改动）：

- `InstanceRow` 新增 `engine` 字段（可空）。为空时由 dialect 推导：
  postgresql→postgresql、trino→trino、mysql→mysql；显式 `engine: starrocks`
  时 `dialect` 必须为 `mysql`（派生方言固定映射，不让用户自选
  「引擎 StarRocks + 方言 trino」这类不可能组合）；
- `starrocks` 实例的元数据采集复用 MySQL introspector（兼容 MySQL 协议与
  `information_schema`），缺省端口 9030，`database` 语义同 MySQL（库名）；
- 实例 CRUD / passwordRef / X-Api-Key 行为不变；
- **Hive / SparkSQL 本批不加枚举值**：避免「枚举有值但采集/执行都不可用」
  的半支持状态，批 2 连同方言 profile 一起加。

## 7. 执行与护栏（mask-query）

### 执行器 SPI

按引擎一个 `QueryExecutor` 实现，负责拼 JDBC URL、建连、执行、取元数据：

| engine | JDBC 驱动 | 改写方言（dialect） | 缺省端口 |
|---|---|---|---|
| postgresql | org.postgresql:postgresql（已在仓库） | postgresql | 5432 |
| mysql | com.mysql:mysql-connector-j（已在仓库） | mysql | 3306 |
| starrocks | com.mysql:mysql-connector-j（协议兼容） | mysql | 9030 |
| trino | io.trino:trino-jdbc:446（已在仓库） | trino | 8080 |

三种驱动都是现成依赖（`--pull-metadata` 已引入），StarRocks 只是同一驱动的
另一种 URL 形态。执行器按 `engine` 分发，改写方言取实例 `dialect`。

### 连接策略与密码红线

- **每次查询新建连接、用完即关**，不做连接池：同步 + 硬限模式下查询速率
  被超时/行数封顶，内网建连毫秒级足够，且免去实例凭据变更后的缓存失效；
  池化留作后续优化；
- 密码沿用 passwordRef 约定：mask-query 部署侧本地解析
  `SQLMASK_DS_<INSTANCE>_PASSWORD` 环境变量（与 mask-metadata 同一约定、
  同一组环境变量）；密码只用于建连，不进日志、不进错误消息、不进审计；
- 连接以只读打开：`Connection.setReadOnly(true)`，与改写侧
  `WRITE_STATEMENT` 拒绝形成双保险。

### 护栏链（一次查询从外到内）

1. **每实例并发信号量**（`query.max-concurrent-per-instance`，默认 10）：
   超出直接快速失败 `QUERY_BUSY`，不排队——排队会让同步 API 的超时语义
   变模糊；
2. **语句超时**：`Statement.setQueryTimeout()`（`query.timeout-seconds`，
   默认 30，纯服务端配置，请求级不开放超时覆盖）；超时 →
   `statement.cancel()` → `QUERY_TIMEOUT`；
3. **最大行数**：`Statement.setMaxRows(生效 maxRows + 1)` 驱动侧截断；
   应用侧读到第 maxRows+1 行即停止拉取并置 `truncated: true`；
4. **流式读取**：统一 fetchSize（`query.fetch-size`，默认 500）。
   引擎细节：PostgreSQL 需 `autoCommit=false` 才真流式（只读事务读完
   rollback，无副作用）；MySQL 走流式游标参数（`useCursorFetch` 或
   `Integer.MIN_VALUE`，实现取一，spec 不锁定）；Trino 驱动天然分页拉取；
5. **断连取消**：Servlet 异步 API 无可移植的断连回调，一期以「语句超时 +
   WebAsyncTask 容器兜底超时（`onTimeout` → `statement.cancel()`）」近似达成
   ——无人认领的查询至多多跑一个兜底窗口即被取消；真正的「客户端断开即取消」
   留待容器特定方案（Tomcat NIO 事件 / Jetty error dispatch）。

### 执行时序

鉴权 → 查实例（mask-metadata）→ 校验 connection 存在 → 按实例改写
（mask-core，含 `kind` 判定）→ 解析密码 → 建连执行 → 流式读至行数上限 →
关连回滚 → 组装响应 → 异步记 QUERY 审计。

## 8. 鉴权与主体语义

- mask-query 要求 `X-Api-Key`，key 来自环境变量 `SQLMASK_QUERY_API_KEY`，
  **未配置 = 全 401**（fail closed，与 mask-metadata 数据面行为一致，
  而非 mask-policy 管理面的「未配置放行」——这是直接导出真实数据的服务）；
- 主体语义：`user` / `groups` 是上游平台**声明的值**，mask-query 原样
  透传给 mask-core 做策略匹配；未传 = 匿名主体（仅 `*` 策略命中），与
  `/api/rewrite` 完全一致；系统不做身份验证，文档与审计明示该口径
  （与审计 spec「不做多租户身份体系」一致）。

## 9. 审计（QUERY 事件）

- mask-query 引入 `mask-audit` 共享模块，复用同一队列、写入管道与当日
  索引族，`eventType` 枚举扩一个值 `QUERY`；
- 事件内容：公共信封（`@timestamp` / `outcome` / `actor{kind: API_KEY,
  user}` / `instance`）+ 类型专属字段：`engine`、`dialect`、`rowCount`、
  `truncated`、`masked`、`rowFiltered`、`elapsedMs`、截断 SQL + SQL hash
  （与 REWRITE 事件同一截断规则）；密码红线照旧：任何字段不含密码与
  API Key 原文；
- **责任边界——不双记**：改写阶段失败只由 mask-core 记 `REWRITE`
  （FAILURE），mask-query 不发事件；从执行阶段起（成功 / `QUERY_TIMEOUT` /
  `QUERY_ERROR` / `QUERY_BUSY`）才由 mask-query 发 `QUERY` 事件。一条
  链路每个环节最多一条事件；
- 可观测性对齐现有口径：限频日志，不做指标端点。

## 10. 测试策略

### 单测（mask-query，无外部依赖）

- 契约校验：缺 `instance` / `sql`、多语句拒绝、`maxRows` 钳制、
  `WRITE_STATEMENT` 判定（改写响应 `kind` 桩）；
- 护栏逻辑：假 JDBC 桩验证 maxRows+1 截断判定、超时路径映射
  `QUERY_TIMEOUT`、并发信号量命中 `QUERY_BUSY`；
- 执行器 SPI 映射表：engine → 驱动 URL / 改写方言 / 缺省端口
  （含 starrocks → mysql 方言）。

### 全链路 IT（嵌入式 PG 真库，沿用仓库 embedded-postgres 模式）

- IT 内随机端口起 mask-core / mask-metadata / mask-policy 上下文 +
  嵌入式 PG 作目标库（PG 内装真 UDF 的 SQL 函数实现），mask-query 打
  HTTP 走完整链路；
- 覆盖：脱敏结果真库执行正确（UDF 真跑）、行过滤 + 脱敏叠加、
  `truncated` 判定、`pg_sleep` 触发 `QUERY_TIMEOUT`、QUERY 审计事件
  （审计记录器用桩，不依赖真 ES）。

### 其余引擎真库验收（docker compose profile）

- 沿用 `docker/` + compose 模式起 MySQL / Trino / StarRocks，跑
  **golden 查询清单**：从仓库现成 TPC-DS 查询集（`mask-core/tpcds`）挑
  代表性语句 + 手写边界查询（聚合列脱敏、LIMIT、行过滤叠加），断言
  「改写产物 → 真库执行 → 脱敏结果」；**StarRocks 的兼容承诺即
  「MySQL 方言改写产物的可执行子集」，以这份清单为准**；
- CI 无这些服务时按 profile 跳过（与现状一致）。

### mask-core 侧回归

- 按实例改写端点：服务模式装配、`kind` 判定（SELECT / INSERT_SELECT /
  CTAS）、未配服务模式报 `CONFIG_ERROR`、实例不存在透传；
- 现有内联 `POST /api/rewrite` 行为不变的回归测试。

## 11. 配置项汇总（mask-query，前缀 `query.`）

| 配置 | 缺省 | 说明 |
|---|---|---|
| `query.timeout-seconds` | 30 | 单语句超时（纯服务端配置） |
| `query.max-rows` | 1000 | 请求未传 maxRows 时的生效值 |
| `query.max-rows-hard` | 10000 | maxRows 钳制上限 |
| `query.fetch-size` | 500 | 流式读取批大小 |
| `query.max-concurrent-per-instance` | 10 | 每实例并发信号量 |

环境变量：`SQLMASK_QUERY_API_KEY`（mask-query 鉴权，未配置全 401）、
`SQLMASK_REWRITE_API_KEY`（mask-core 新端点可选 key，未配置放行）、
`SQLMASK_DS_<INSTANCE>_PASSWORD`（沿用，mask-query 本地解析）。

## 12. 分批路线

- **批 1（本 spec）**：mask-query + mask-core 按实例改写 + `kind` 字段 +
  starrocks 引擎 + 四引擎执行与护栏 + QUERY 审计 + 上述测试；
- **批 2（另立 spec）**：改写器新增 hive / sparksql 方言 profile
  （Calcite 有对应 conformance，mask-lite 的方言抽象已预留接缝）、
  mask-metadata 枚举与采集、对应 JDBC 执行器（HiveServer2 / Spark
  ThriftServer）、真库 golden 清单。
