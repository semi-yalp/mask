# sql-mask v3 目标与范围设计

- 日期：2026-09-21
- 前置：v2 目标定稿 → `docs/superpowers/specs/2026-09-21-sql-mask-v2-goal-design.md`（v3 以 v2 改写服务为基座）；现有仓库 `mask-policy-server` 定位为「成熟参考来源」（沿用 v1/v2 对存量代码的定位）
- 状态：已实现（2026-09-22，实施计划见 `docs/superpowers/plans/2026-09-21-sql-mask-v3-policy-service.md`，实现对照见文末 §12）

## 1. 目的与决策记录

v2 交付的是改写服务（只改写不查询 + 元数据导入 + 策略维持 YAML 配置）。v3 把**策略从配置文件提升为独立策略微服务**，对标 Apache Ranger 的 Admin/Plugin 分工（Admin=策略所有者，Plugin=引擎内/下游消费者）；同时引入引擎直连、多实例、每策略多版本回退、元数据联想、通配符资源。本期不考虑认证。

本次确认的关键决策（含对 v2 的显式翻转）：

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 服务形态 | 独立策略微服务（8081）+ 改写服务（8080）双服务；策略为服务化 CRUD |
| D2 | 是否推翻 v2 D5 | 是：v2「策略维持配置文件、不做服务化」被推翻；`policies.yaml` 保留为本地/离线路径与服务并存 |
| D3 | 设计基座 | 以 v2 文档状态重新设计；存量 `mask-policy-server`（实例/策略/UDF CRUD、主体维度、生效配置编译、API Key 鉴权）仅作参考来源 |
| D4 | 引擎连接 | 策略服务**直连查询引擎**（JDBC，复用 v2 `mask-introspect`）；建实例以连接测试成功为前置（fail-closed）；另有独立预检端点 |
| D5 | 多实例 | 实例 ↔ 连接的查询引擎一一对应，`dialect` 区分引擎类型——不同引擎=不同策略实例；同方言允许多副本实例 |
| D6 | 操作范围 | 策略 `accessType` 枚举，本期**仅 `SELECT`**；非 SELECT 访问治理为非目标 |
| D7 | 多版本 | **每策略独立版本**（Ranger 式 `policy_version` 不可变历史），可单条回退；实例级 `config_version` 仍驱动客户端刷新 |
| D8 | 回退语义 | **版本只增、历史不可变**；回退=生成内容取自某历史版本的新版本（不回退指针） |
| D9 | 元数据取值 | 联想/直连是「取元数据」的通道（快照缓存可选、实时查询兜底）；**手填始终允许**；资源有效性校验软化（取不到不硬拒） |
| D10 | 通配符 | 资源选择器字段支持 glob（`*`/`?`，复用匹配引擎）；策略定义期不强制资源存在 |
| D11 | 认证 | **本期不考虑认证**（显式非目标）；沿用无认证现状，不引入 API Key/用户目录/多租户 |
| D12 | YAML 路径 | 保留为本地/离线路径（CLI/内联 rewrite 不破坏），与服务并存 |
| D13 | 方言 | 延续 v1/v2 五方言：PostgreSQL / MySQL / Trino / Hive / SparkSQL |
| D14 | 版本冲突 | 并发写策略按乐观版本校验 fail（`CONCURRENT_MODIFICATION`） |

v3 不做 v3 之外的预设；v4 占位见 §11。

## 2. 一句话目标

> v3 在 v2 改写服务基座上交付一个**独立策略微服务**：策略服务是**策略所有者**——通过直连 PostgreSQL / MySQL / Trino / Hive / SparkSQL 建立并校验查询引擎连接、按引擎维护多策略实例、提供策略 CRUD（本期仅 `SELECT`）、**每策略多版本并支持版本回退**、基于引擎元数据提供**表/列/UDF 联想**与**通配符资源**；改写服务降为纯策略消费方（按实例级 `config_version` 轮询生效配置），内联 YAML 本地路径保留并存；本期不考虑认证。

## 3. 范围

### 3.1 v3 能力清单（in scope）

1. 独立策略微服务 `mask-policy-server`（8081）：连接管理、实例 CRUD、策略 CRUD、版本/回退、联想、生效配置编译。
2. 引擎直连：五方言 JDBC 只读（复用 v2 `mask-introspect`），连接测试状态机。
3. 多实例：每引擎（及副本）一实例；实例携带连接配置（密码加密落库，继承 v2 加密子系统）与连接状态。
4. 策略 CRUD：`accessType=SELECT`、资源选择器（catalog/schema/table/columns 支持 glob）、主体（users/groups）、datamask（UDF+参数）/ row_filter。
5. 多版本与回退：每策略版本历史（不可变）、回退生成新版本、`config_version` 联动。
6. 元数据联想：表/列/UDF 建议端点；快照缓存可选 + 实时直连兜底；手填始终允许。
7. 通配符资源解析与数据面匹配。
8. 数据面：按主体编译生效配置（SELECT-only）+ `mask-server` instance 模式消费（轮询/LRU/stale-but-available/fail-closed）+ CLI `--instance`。
9. `policies.yaml` / `metadata.yaml` 本地/离线路径保留并存。

### 3.2 明确非目标（v3 依旧不做）

- **认证与多租户**：无 API Key、无用户目录、无权限模型（本期不考虑认证）。
- **非 SELECT 访问治理**：`accessType` 仅 `SELECT`；INSERT/UPDATE/DELETE/DDL 的允许/拒绝治理不做。
- **跨引擎转写**（续 v1/v2）：输入输出同方言。
- **任何业务 SQL 执行**：服务不执行业务 SQL；唯一真实连接只用系统目录、只读。改写侧零 JDBC（v1/v2 纪律）。
- **Web UI**：内置管理页面不做（联想与管理均为 REST）。
- **推送式配置分发**：轮询 + 手动刷新（沿用 v2/参考语义）。
- **元数据快照强制**：快照是可选缓存，不是事实源（事实源=直连+手填）。
- **ES 审计与完整指标平台**：继承 v2 的 `actuator health/info` 基础可观测，不引入 ES 审计或 Prometheus 指标体系。

### 3.3 两条连接边界（重要）

| 链路 | 是否连接引擎 | 用途 | 只读 | 处理 |
|---|---|---|---|---|
| 改写管线（mask-server / mask-core） | 否 | 文本 → 文本 | — | 零 JDBC 依赖（v1 已去，续） |
| 策略服务 → 引擎 | 是 | 连接测试 + 联想/元数据取值 | 是（仅系统目录：`DatabaseMetaData` / `information_schema` / `pg_catalog`） | 复用 `mask-introspect`；连接仅在该用例临时解密；测试失败诊断不含密码 |

策略服务的「连接」与改写服务解耦：改写请求不携带连接信息；连接信息只在所属策略服务内使用。

## 4. 架构总览（v2 模块图 + v3 新增）

```
mask-parser        # v1（codegen 解析器 + 关键字注册表）
mask-core          # v1（改写管线/方言/血缘/行过滤/YAML；v3 新增 instance 模式消费端口）
mask-cli           # v1（CLI + fat jar；v3 新增 --instance 消费）
mask-embed-api     # v1 宿主侧瘦接口（不动）
mask-embed         # v1 classloader 隔离（不动）
mask-server        # v2 改写服务(8080)：内联 YAML 本地路径保留；v3 加 instance 模式 = 纯策略消费方
mask-introspect    # v2 五方言 JDBC 只读连接器（v3 被策略服务依赖：直连/联想复用）
mask-policy        # v3 新增：策略领域库（模型：accessType/版本/glob 资源选择器；匹配引擎；校验；YAML loader）
mask-policy-server # v3 新增：策略微服务(8081)：连接管理 + 实例 CRUD + 策略 CRUD/版本/回退 + 联想 + effective 编译

数据库：H2（默认，Docker 卷持久化）| 外部 PostgreSQL（profile），Flyway 迁移 —— 沿用 v2 存储形态
```

依赖单向无环：

- `mask-policy-server → {mask-policy, mask-introspect}`（模型/校验/匹配 + 直连连接器）
- `mask-server → {mask-core, mask-policy}`（改写消费 + 策略本地路径）
- core ↔ policy-server 运行时**仅有 HTTP**（`/api/v1/effective`）。

`mask-policy-server` 内部分层：

```
controller（REST 契约 / springdoc / 错误体）
  └─ application（连接管理、实例、策略编排、版本、联想、effective 编译、密钥管理）
     ├─ core-port（对 mask-introspect 直连适配）
     ├─ secret（继承 v2 AES-GCM 加密：连接密码落库）
     ├─ policy（模型/校验/glob 匹配/版本 —— 复用 mask-policy）
     └─ store（JPA：InstanceEntity / PolicyEntity / PolicyVersionEntity / UdfDefinition / MetadataSnapshot）
```

## 5. 服务能力设计

### 5.1 REST 契约（草案，springdoc 收敛）

统一错误体 `{ "code": "...", "message": "...", "details": [] }`；业务错误 HTTP 400；本期无鉴权。

```
# 连接与实例
POST   /api/v1/connections/test                      # 预检：{connection} → {ok, dialect, latencyMs, warnings[]}；不落库
POST   /api/v1/instances                             # {name, connection, fetchMetadata?}（dialect 在 connection 内）→ 测连通(fail→400 CONNECTION_FAILED 不落库) → 建实例(CONNECTED)
GET    /api/v1/instances[/{name}]                    # 列表/详情（连接状态 + 可选快照摘要；无密文）
PUT    /api/v1/instances/{name}/connection           # 改连接 → 重测 → 状态更新
POST   /api/v1/instances/{name}/connection/test      # 重测存量连接（不改配置）
DELETE /api/v1/instances/{name}                      # 有策略时拒绝（沿用守卫）
POST   /api/v1/instances/{name}/metadata-fetch       # 手动拉表结构入可选快照

# 联想
GET    /api/v1/instances/{name}/suggest?kind=table|column|udf&q=…&schema=…&table=…&limit=…

# 策略（每实例）
POST   /api/v1/instances/{name}/policies                        # 创建（v1）
GET    /api/v1/instances/{name}/policies[/{policy}]             # 列表/详情
GET    /api/v1/instances/{name}/policies/{policy}/versions      # 版本历史（只增不可变）
PUT    /api/v1/instances/{name}/policies/{policy}               # 更新 → 新版本
POST   /api/v1/instances/{name}/policies/{policy}/rollback      # {version} → 新版本（内容取自历史）
DELETE /api/v1/instances/{name}/policies/{policy}               # 删除 → config_version 推进；历史保留

# UDF（沿用参考实现能力，随本期保留）
POST/GET/PUT/DELETE /api/v1/instances/{name}/udfs[/{name}]

# 数据面
GET    /api/v1/effective/{instance}?user=&groups=    # 按主体编译 SELECT-only 生效配置；configVersion 驱动消费方刷新

# 健康
GET    /actuator/health
```

建实例即「先建立连接 → 生成实例」；策略只能挂在已存在实例下（连接 → 实例 → 策略，顺序不可逆）。

### 5.2 引擎连接管理（直连 + 状态机）

- **ConnectionConfig**：`{ dialect, host, port, database|catalog, schema[], username, password, ssl? }`（按方言拼 JDBC URL；PG/MySQL/Trino/Hive/Spark URL 形态沿用 v2 §5.2）。
- **建实例前置（fail-closed）**：`POST /instances` 携带连接即测试；失败 → 400 `CONNECTION_FAILED`，诊断描述不落库、不回显、不记日志明文；实例不创建。可先用 `POST /connections/test` 预检。
- **状态机**：实例创建即 `CONNECTED`；后续重测可转 `FAILED`。状态随实例详情返回；`POST /instances/{name}/connection/test` 更新并返回。
- **密码**：沿袭 v2 加密子系统（AES-256-GCM + 主密钥，接口不回传、日志不落明文、缺失主密钥时凭据能力 fail-closed）；连接测试仅导入/联想用例临时解密。
- **只读纪律**：直连查询仅走系统目录，不执行业务 SQL；`mask-introspect` 为唯一连接器来源（不进改写链路 classloader）。

### 5.3 策略 CRUD 与校验

- **PolicyEntity**：`{name, accessType=SELECT, enabled, priority, resource{catalog,schema,table,columns[]}, subjects{users,groups}, udf, arguments, filterExpr, currentVersion}`。
- 实例内策略名唯一；策略挂接已存在实例。
- 校验（`PolicyValidator`，复用参考实现并软化）：
  - `accessType` 仅 `SELECT` 合法（非 SELECT → 400 `CONFIG_ERROR`）；
  - 资源字段允许 glob（`*`/`?`），非空；
  - **有效性软校验**：能取到实例元数据（快照或 live）时给出候选/提示，但**不硬拒**不存在或手填的名字；列类型已知时做 UDF 类型矩阵校验，未知时仅校验 UDF 存在性与参数个数（软降级）；
  - DATAMASK 的 UDF 四步校验（存在性 → 参数个数 → 标量矩阵 → 列类型[已知时]）沿用；
  - 重叠判定按主体相交放宽（沿用参考语义）。

### 5.4 多版本与回退（Ranger 式）

- **版本表 `policy_version`**（不可变）：`{instance, policy, version, content_json, change_type(CREATE|UPDATE|ROLLBACK), source_version?, created_at}`，`UNIQUE(policy, version)`。
- **更新**：`PUT /policies/{name}` → 新内容落一条新版本（`version+1`），当前策略指向新版本，实例 `config_version` 推进。
- **回退**：`POST /policies/{name}/rollback {version:N}` → 取历史 N 的内容写入新版本（`version+1`，`change_type=ROLLBACK`，`source_version=N`），实例 `config_version` 推进。**版本只增，历史不可变；回退不改变历史，不产生「回退再回退」的特例**。
- **版本冲突**：写入时校验乐观版本；并发冲突 → 400 `CONCURRENT_MODIFICATION`（重试语义文档化）。
- **删除**：删当前策略 → `config_version` 推进；历史保留（审计）；同名重建续用版本序列。
- **查询**：`GET /policies/{name}/versions` 返回有序历史（含 change_type、时间、source_version）。

### 5.5 联想与元数据取值（快照 / 实时 / 手填）

- **取值通道**（已与用户确认）：
  1. **直连 + 联想**：策略服务直连引擎，从系统目录取名 → 联想端点返回候选；`MetadataSnapshot`（可选缓存）存在时优先取快照；
  2. **手填**：始终允许，联想只是辅助，不强制存在。
- **联想端点**：`GET /instances/{name}/suggest`
  - `kind=table`：候选表（按 schema 过滤；q 支持前缀/通配）；
  - `kind=column`：候选列（需 table/schema 上下文）；
  - `kind=udf`：候选 UDF；
  - 响应 `{source: snapshot|live, items:[{name, schema?, table?, type?, rawType?}]}`；
  - 无快照 → live 降级（只读系统目录，limit 上限、超时并入 `CONNECTION_FAILED` 诊断）；快照可经 `metadata-fetch` 手动/定期刷新。
- **快照语义**：`MetadataSnapshot` 是可选缓存（表/列名 + rawType），不是事实源；策略定义与数据面匹配不依赖其必须存在；导入失败不动存量快照（幂等语义沿用 v2）。

### 5.6 通配符资源

- 资源选择器字段（catalog/schema/table/column）支持 glob（`*`/`?`），语义复用 `mask-policy` 匹配引擎（与 `policies.yaml` 的 glob 语义一致）。
- 数据面编译：通配资源在编译期**展开为明确的表/列集**进入生效配置（必要时保留 glob 索引供查询时匹配），改写层按展开结果决策——**不把 glob 抛给引擎猜测**。
- 联想对 glob 输入（如 `crm.*.customer*`）返回已存在匹配项。

### 5.7 数据面：生效配置编译与消费

- `GET /api/v1/effective/{instance}?user=&groups=`：对实例已启用、`accessType=SELECT`、主体命中的策略按主体编译生效配置（一表一条 row_filter、列不重叠 datamask、含每列 UDF/参数）；响应 `{configVersion, dialect, tables[], rowFilters[], udfs[]}`（复用参考编译产物形状）。
- **config_version**（实例级）：任何策略/表/UDF/回退变更推进；消费方据此缓存刷新。
- **mask-server instance 模式**（新增消费端）：`/api/v1/rewrite` 可带 `instance`（与内联 `metadataYaml`/`policyYaml` 二选一，同时给出或缺省 → 400 `CONFIG_ERROR`）；改写服务按实例+主体拉取并缓存生效配置（每主体 LRU、`configVersion` 变化全量刷新、stale-but-available、无缓存且服务不可达 → fail-closed `POLICY_SERVICE_UNAVAILABLE`，绝不静默不脱敏）；配置项 `policy.service.url` / `policy.service.poll-interval-ms`。
- CLI：`--instance <name> --policy-service <url>` 与 `--metadata` 互斥；一次性拉取（无缓存）。
- 内联 YAML 本地路径完全不动（§3.2、§7）。

### 5.8 错误契约

- 沿用风格：400 业务错误 + `{code, message, details[]}`。
- 新增：`CONNECTION_FAILED`（引擎连接/测试失败）、`VERSION_NOT_FOUND`（回退到不存在的版本）、`CONCURRENT_MODIFICATION`（乐观冲突）。
- 沿用：`CONFIG_ERROR`、`POLICY_INSTANCE_NOT_FOUND`、`POLICY_SERVICE_UNAVAILABLE`。
- 一律不含密码明文；凭据相关错误消息脱敏。

## 6. 数据模型（库表）

```
instance(id, name UNIQUE, dialect, connection_json, connection_status,
         config_version, created_at, updated_at)
policy(id, instance_id FK, name, access_type, enabled, priority,
       resource_json, subjects_json, udf, args_json, filter_expr,
       current_version, updated_at)                        -- UNIQUE(instance_id, name)
policy_version(id, instance_id FK, policy_id, version, content_json,
       change_type, source_version, created_at)            -- UNIQUE(policy_id, version)，不可变
udf_definition(id, instance_id FK, name, signature_json, created_at)  -- UNIQUE(instance_id, name)
metadata_snapshot(instance_id PK, captured_at, tables_json)            -- 可选缓存
```

- Flyway；H2/PG 共用迁移脚本（沿用 v2）。
- UDF 注册表沿用参考实现（REST / 签名存储 / 四步校验 / 引用守恒守卫）。

## 7. 与 v1/v2 的衔接（复用映射 + 决策翻转）

| 源 | 复用 / 变化 |
|---|---|
| v1 mask-core（改写管线/方言/血缘/行过滤/YAML） | 原样；v3 仅新增 instance 模式消费端口（数据面） |
| v1 mask-parser / mask-cli / mask-embed* | 原样；CLI 新增 `--instance` |
| v2 mask-server | 保留改写 + 内联 YAML 本地路径；**服务化的实例元数据存储不再作为数据面事实源**（instance 模式指向策略服务） |
| v2 mask-introspect | 原样；v3 被 `mask-policy-server` 依赖（直连/联想复用） |
| v2 加密子系统（AES-GCM + 主密钥） | 原样；用于策略服务连接密码落库 |
| v2 决策 D5 | **被 v3 推翻**：策略由配置文件 → 独立微服务 CRUD；YAML 保留本地/离线路径 |
| 存量参考 `mask-policy-server` | 仅参考（模型/校验/编译器/HttpMetadataStructureFetcher 等），v3 按本规格重新设计 |
| `policies.yaml` / `metadata.yaml` 本地路径 | 保留并存（本地/离线） |

v2 文档 §13 的 v3 占位指向本文档。

## 8. 验收标准

1. **连接管理**：五方言 `connections/test` 与 `POST /instances` 对嵌入/桩库 golden（连接成功建实例、失败 400 `CONNECTION_FAILED` 不落库、重测状态流转）；失败诊断与日志无密码断言。
2. **策略 CRUD**：策略增/删/改/查契约测试全绿；`accessType=SELECT` 强制（非 SELECT 拒绝）；UDF 校验软降级（列类型未知只验存在性/个数）。
3. **多版本/回退**：每更新生成新版本且历史不可变（重放断言）；回退到任意历史版本内容正确、`change_type=ROLLBACK`、`source_version` 正确；`config_version` 每次变更推进；并发冲突 400 `CONCURRENT_MODIFICATION`；删除保留历史 + 同名续用版本序列。
4. **联想**：快照通道与 live 通道 golden（表/列/UDF 候选、glob 输入建议）；无快照降级 live；手填任意值不被拒。
5. **通配符**：资源选择器 glob 匹配矩阵（`*`/`?`、跨 schema、跨列）；数据面按展开结果决策（不把 glob 丢给引擎）。
6. **数据面**：按主体编译（匿名仅 `*`、user/group 精确命中）；SELECT-only；客户端 instance 模式（版本轮询刷新、stale-but-available、无缓存 fail-closed、多主体缓存隔离）；CLI `--instance`。
7. **YAML 回归**：本地 `policies.yaml` / 内联路径全部既有行为零变化（回归全绿）。
8. **回归与工程化**：v2 全部测试在 v3 基座保绿；工程化门（v2 §7：checkstyle/spotless/spotbugs/rat/jacoco/enforcer/-Werror）继续适用。

## 9. 任务拆分（粗粒度里程碑）

| 里程碑 | 内容 | 依赖 / 说明 |
|---|---|---|
| **M0** 前置盘点与骨架 | 以 v2 状态为基线；盘点参考实现可复用件；建 `mask-policy` / `mask-policy-server` 模块骨架 + Flyway | v3 前置 |
| **M1** 连接管理 | ConnectionConfig + 复用 mask-introspect 五方言直连 + `connections/test` + 实例 CRUD（连接状态机）+ 密码加密落库（继承 v2 secret） | 依赖 M0 |
| **M2** 策略 CRUD + 版本 | 模型（accessType/glob 资源/主体）/ 校验软化 / `policy_version` 表 + 更新与回退端点 / config_version 联动 / 错误码（VERSION_NOT_FOUND、CONCURRENT_MODIFICATION） | 依赖 M0 |
| **M3** 联想 + 通配符 | `suggest` 端点（快照/live）+ 可选快照 `metadata-fetch` + glob 资源解析与数据面展开/索引 | 依赖 M1 |
| **M4** 数据面接线 | effective 编译（主体过滤 + SELECT-only）+ mask-server instance 模式消费 + CLI `--instance` + 客户端缓存/轮询/fail-closed | 依赖 M2 + M3 |
| **M5** 验收与文档 | §8 全门绿；API 文档归档；部署（compose 双服务）；README 更新 | |

## 10. 决策日志与风险

| # | 类型 | 内容 | 处置 |
|---|---|---|---|
| R1 | 范围纪律 | 认证不进入本期 | §3.2 显式非目标，防蔓延 |
| R2 | 安全 | 连接密码落库与主密钥运维责任 | 继承 v2 secret 子系统；fail-closed；诊断脱敏 |
| R3 | 有效性 | 元数据不强制快照 → 打错的名字可能被放行（fail-open 隐患） | 软校验：能取到就建议/提示、仅告警不硬拒；数据面按配置 glob 精确执行（不猜），文档写明「无元数据时有效性不保证」 |
| R4 | 一致性 | 双通道联想（快照/live）时效不一致 | live 兜底 + 快照可选刷新；响应 `source` 字段标识；文档时效说明 |
| R5 | 数据增长 | 版本表每更新一条只增 | 按实例保留/清理策略（文档化）；审计与历史分离 |
| R6 | 并发 | 多管理员并发改同一策略 | 乐观版本校验 `CONCURRENT_MODIFICATION` + 重试语义 |
| R7 | 通配符 | 通配表在改写层解析 | 编译期展开为明确表/列集进生效配置，不把 glob 丢给引擎 |
| R8 | 架构 | 「拒绝查询执行」纪律被直连能力侵蚀 | §3.3 双链路：改写零 JDBC；直连仅系统目录、仅连接/联想用例 |
| R9 | 现状迁移 | 参考实现与 v3 规格差异 | 规格为准重新设计；参考仅作成熟参考来源 |

## 11. 未决 / v4 占位

（占位，待用户描述。候选：认证与多租户、非 SELECT accessType、Web 管理 UI、推送式配置分发/长连接、策略成效可视化、审计事件，等。）

## 12. 实现对照（2026-09-22 收口）

按用户确认（「另起新模块重写」+「按现实收敛」）落地的与正文的差异：

| # | 正文设想 | 实际落地 | 理由 |
|---|---|---|---|
| 1 | 新建 `mask-policy`/`mask-policy-server` 模块 | 在原模块路径**整包重建**（旧实现入 git 历史），mask-core 依赖契约逐字节不变 | 同 reactor 不能有同名模块；契约稳定使改写引擎全程可编译 |
| 2 | 五方言直连 | **postgresql/mysql/trino** 三方言（Hive/SparkSQL 延后） | 仓库尚无 Hive/Spark 的解析器/introspector/驱动四件套 |
| 3 | 连接密码 AES-GCM + 主密钥 | **passwordRef**（存环境变量名，用时解析） | 仓库无加密子系统；与 mask-metadata/mask-query 惯例一致；本期无认证 |
| 4 | H2 默认 + Flyway | **PostgreSQL + `spring.sql.init`**（`schema.sql` 幂等 + 追加式 ALTER） | 与仓库三服务一致，零新基建 |
| 5 | `/api/v1/...` 前缀 | 沿用**无版本前缀**（`/api/instances`、`/api/effective/{i}`…） | mask-core 消费者已按此调用，字节兼容 |
| 6 | glob 含 `?` | `GlobMatcher` 升级支持 `?`（单字符），`*` 语义不变 | v3 新增 |
| 7 | `POST /api/instances/{i}/import-metadata`（metaserver HTTP 导入） | **移除**，由「直连 + 联想 + 手填」替代 | 用户决策：元数据按需直连/联想获取或直接填写 |
| 8 | 可选 API Key 过滤器保留 | **不引入任何过滤器**（本期无认证） | spec §3.2 显式非目标 |
| 9 | — | `policy_version` 按 (instance, policy_name) 键控、与 policy 行解耦；删除保留历史、同名重建续版本 | spec §5.4 语义的正确实现 |
| 10 | — | `EffectiveConfigResponse` 契约不变；编译 warnings 仅日志不进响应 | mask-core 消费者字节兼容 |

验证：`mask-policy` 81、`mask-core` 550、`mask-policy-server` 89 测试全绿；
端到端 compose 冒烟因环境无 Docker/PG 降级为分层验证（HTTP 全流程 MockMvc +
真实 PG 存储层/直连层集成测试），缺口已记录。