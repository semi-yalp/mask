# 元数据微服务设计方案

日期：2026-09-07
状态：设计已与用户逐段确认（模块与数据模型 / API / 策略服务改造与拉取 / 错误安全 / 存储部署与测试，全部通过）
前置文档：`2026-09-06-policy-service-design.md`（策略微服务）、`2026-09-06-metadata-introspection-multi-engine-design.md`（三引擎采集）

## 1. 背景与目标

策略微服务设计已定稿并把"引擎实例 + 表结构元数据"存在策略服务自己的 PG 里
（`policy_instance`/`instance_table`/`instance_column`）。本设计把**元数据部分也拆成
独立微服务**：实例登记、表结构存储、三引擎采集（`io.sqlmask.introspect` 能力的服务化）
收敛到一个元数据服务，成为"实例 + 表结构"的**唯一事实源**；策略服务收缩为
"纯策略 + 编译"，表结构改为从元数据服务拉取。

达成：

1. **元数据可复用**：表结构以存储态/编译态对策略服务之外的系统开放；
2. **采集能力集中**：JDBC 驱动、连接凭据、网络访问这类"重"依赖只落在元数据服务；
3. **职责单一**：策略服务不再碰连接与采集，只管策略与编译；
4. **改写引擎零改动**：`/api/effective/{instance}` 契约不变。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 职责边界 | 采集 + 存储 + 提供：实例 CRUD、表结构存储、三引擎采集、写时类型校验都归元数据服务 |
| 实例归属 | 实例（name、dialect、连接引用、表结构）整体归元数据服务；策略服务只存策略、挂实例名 |
| 连接信息 | 存引用不存明文：实例登记存环境变量名（password_ref），采集时服务侧解析 |
| 集成拓扑 | 两级各拉各的（方案 A）：改写引擎→策略服务（不变）；策略服务→元数据服务（新增） |
| 兜底语义 | 每级定期拉取（默认 30s），**拉不到就用上个 version**（stale-but-available）；无任何缓存则拒绝，绝不 fail-open |

### 1.2 明确不做（YAGNI）

- 元数据服务管理 UI（纯 REST）；
- 一次性传参采集的服务端点（core CLI `--pull-metadata` 与 core Web `/api/metadata/pull` 已覆盖且零回归保留）；
- 定时自动采集调度（连接引用的存储已为其铺路，v1 只做手动触发 `collect`）；
- 跨服务删除保护（元数据服务不知道策略服务的策略引用；删除放行 + 文档警示，fail-closed 兜底）;
- 跨库/跨 catalog 一次采集（延续多引擎设计非目标）；
- 元数据历史版本与审计（仅 `metadata_version` 单调递增）；
- 改写 UI"从元数据服务选实例导入"（v1 改写侧零改动）；
- 资源选择器通配符等（延续策略设计 v1 范围）。

## 2. 模块形态与代码落位

```
mask/
├── mask-core/        ← 不动：解析/血缘/改写/方言/server/CLI + introspect 库（留在 core）
├── mask-policy/      ← 改造：去掉实例与表结构存储，策略挂实例名
├── mask-metadata/    ← 新增：元数据微服务（独立 Spring Boot fat jar，默认 8082）
└── pom.xml           ← 三个子模块
```

- **依赖方向**：`mask-metadata → mask-core`、`mask-policy → mask-core`，两服务互不依赖，core 对两者零依赖；
- **`introspect` 包留在 core**：core 的 CLI `--pull-metadata` 与 `POST /api/metadata/pull`（改写页面导入）零回归。mask-metadata 的采集端点复用同一库（`MetadataIntrospector` 注册表、三引擎实现、类型映射、`MetadataYamlGenerator`）——与 mask-policy 复用 `YamlConfigLoader` 同款先例；
- **类型同源不漂移**：元数据服务的数据面产物 = 现 YAML `tables` 段的等价 JSON，策略服务与元数据服务都用 core 配置模型（`MaskingConfig`/`TableMetadata`）反序列化，编译产物直接进现有改写管线。

## 3. 数据模型与版本机制

存储：自有 PostgreSQL database（如 `mask_metadata`），三张表。列类型存**该引擎的类型名原始文本**（与多方言"类型名跟随引擎"一致），写入时按 dialect 的 TypeResolver 校验、存原文不存解析态：

- `meta_instance`：`name`（唯一、创建后不可变）、`dialect`（postgresql/mysql/trino，同时是采集引擎，创建后不可变）、连接与采集口径字段（host、port、database、db_user、`password_ref`、sslmode、connectTimeoutSeconds、`schemas`（JSONB，可空=不过滤）、`include_views`——**整组可空**：YAML 导入的实例可以没有连接信息，此时不允许 collect）、`metadata_version`（单调递增）、时间戳；
- `meta_table`：instance_id + catalog/schema/name（实例内唯一）；
- `meta_column`：table_id + name、ordinal、type 原文（precision/scale 含在原文中）。

**版本机制**：实例的任何变更（登记、连接/口径更新、采集覆盖、导入）在同一事务内 `metadata_version + 1`。这是策略服务轮询的锚点，单调递增保证轮询不丢更新。

**密码引用语义**：`password_ref` 是环境变量名（推荐惯例 `SQLMASK_DS_<INSTANCE>_PASSWORD`，自由填写）。采集时服务侧 `getenv` 解析；变量未设置 → 采集失败 `METADATA_CREDENTIAL_UNAVAILABLE`，错误信息只说"引用的环境变量未设置"，**不回显引用值、不泄 URL**。任何接口不回显密码；`password_ref`（变量名本身）可回显。

## 4. API 设计

鉴权沿用策略服务的静态 API Key 模式（管理面/数据面可用不同 Key，401 统一处理）；错误响应沿用 core `ApiExceptionHandler` 风格 `{code, message, details[]}`。

### 4.1 管理面（管理员/控制台）

| 端点 | 说明 |
|---|---|
| `GET/POST /api/instances` | 列表 / 创建（name、dialect、连接与采集口径字段） |
| `GET/PUT/DELETE /api/instances/{name}` | 详情（表结构全量 + 连接引用，变量名可回显）/ 更新（连接与口径等可变字段）/ 删除（硬删；文档警示策略服务侧同名实例的策略将失效、编译 fail-closed） |
| `POST /api/instances/{name}/collect` | 触发采集：用已存连接引用 + 口径构建 `ConnectionSpec`（strict=false）→ `MetadataIntrospectors.byEngine(dialect)`；成功 = 同事务覆盖该实例全部表结构 + `metadata_version+1`，响应 `{tableCount, columnCount, warnings}`；**失败不动存量表结构** |
| `POST /api/instances/import` | 老 YAML 导入（只吃 `tables` 段）：`{name, dialect, 连接与口径字段?, metadataYaml}` → 建实例 + 表结构 |

两个硬规则：

1. **导入遇表声明 `rowFilter` 字段 → 400 拒绝并指路**（"请在策略服务配置 row_filter 策略"）。行过滤是安全语义，静默丢弃 = 意外放开数据——与项目 fail-fast 风格一致；
2. 一次性传参采集（不落库）仍由 core CLI / core Web 承担；元数据服务的采集一律走已存引用，保证可重复、可审计。

### 4.2 数据面（策略服务等消费方）

| 端点 | 说明 |
|---|---|
| `GET /api/metadata/instances` | 实例清单（name、dialect、metadataVersion） |
| `GET /api/metadata/instances/{name}` | 表结构全量：`{instance, dialect, metadataVersion, tables:[{catalog, schema, name, columns:[{name, type}]}]}`——现 YAML `tables` 段等价 JSON，类型为引擎原文 |
| `GET /api/metadata/instances/{name}/version` | 轻量版本号 |

### 4.3 错误码（元数据服务）

| 场景 | 码 |
|---|---|
| 实例不存在 | `METADATA_INSTANCE_NOT_FOUND`（404） |
| 实例已存在 | `METADATA_INSTANCE_EXISTS`（409） |
| 引用的环境变量未设置 | `METADATA_CREDENTIAL_UNAVAILABLE`（400） |
| 采集失败（连接失败等上游数据源问题） | `INTROSPECT_ERROR`（502） |
| 类型校验失败 / rowFilter 字段 / 参数缺失 | `CONFIG_ERROR`（400） |
| API Key 缺失/错误 | 401 |

## 5. 策略服务改造与两级拉取缓存

```
元数据服务(8082)                 策略服务(8081)                  改写服务
┌───────────────┐  30s轮询version ┌────────────────┐  30s轮询version ┌───────────────┐
│ 实例+表结构      │ ←───────────── │ MetadataClient  │ ←───────────── │ 不变            │
│ metadataVersion │  变则拉全量+缓存 │ 策略 + 编译       │  变则拉全量+缓存 │ PolicyConfig   │
└───────────────┘                └────────────────┘                │  Provider(已有) │
                                  每级：拉不到 → 用上个 version       └───────────────┘
```

**删除**（相对策略服务设计）：`instance_table`/`instance_column` 存储表、实例 CRUD 端点、导入器中"建实例 + 表结构 + rowFilter 转策略"部分。

**保留**：策略 CRUD（`policy` 表挂 `instance_name`）、选择器重叠检测、编译器（选择器展开 / row_filter 回填 / 禁用剔除）、`/api/effective/{name}` 与 `/version` 契约、`configVersion` 机制、改写引擎接入（`PolicyConfigProvider` 双来源、缓存轮询、`POLICY_SERVICE_UNAVAILABLE`）。

**新增 `MetadataClient`**（mask-policy 内）：

- 默认每 30s（可配）轮询各实例 `/version`，版本变化才拉全量 → 进程内缓存「实例名 → (metadataVersion, 表结构)」；
- **拉不到就用上个 version**；实例被删（轮询 404）→ 保留缓存 + 警告日志，编译继续用缓存；
- 进程重启后缓存为空且元数据服务不可达 → **拒绝编译，绝不 fail-open**；
- 懒轮询：实例首次被策略引用时才加入轮询集合。

**写入校验调整**：创建/更新策略时，实例存在性、引用的表/列存在性查 `MetadataClient` 缓存（miss 现拉一次再校验；不可达且无缓存 → 503 拒绝）；选择器重叠、`filterExpr` 白名单等校验不变。

**版本传导**：轮询发现 `metadataVersion` 变化 → 拉全量 → 重编译 → 该实例 `configVersion+1`。生效配置内含表结构，表结构任何变化必然进入产物，无条件传导正确且必要。

**导入器拆分**：老 YAML 迁移两步走——元数据服务 `/api/instances/import` 吃 `tables` 段建实例与表结构；策略服务新增 `POST /api/instances/{name}/policies/import` 吃老 YAML 的 `policies` + `columns` 段（聚合反转为策略实体，语义对齐原设计 §4.1）。

## 6. 错误与安全语义

- **双层绝不 fail-open**：改写引擎对策略服务（已有 `POLICY_SERVICE_UNAVAILABLE`）+ 策略服务对元数据服务（新增 `METADATA_SERVICE_UNAVAILABLE`，对外 `/api/effective` 503）。两级都是"有缓存用缓存、无缓存拒绝"；
- **密码安全三条硬线**：密码只存在于环境变量；库表、日志、API 响应、错误信息均不出现密码与 JDBC URL（复用 core `sanitize` 精神）；采集失败不覆盖存量表结构——"数据面产物永远自洽"的源头保证；
- 错误码汇总：元数据服务见 §4.3；策略服务新增 `METADATA_SERVICE_UNAVAILABLE`（503）；其余沿用策略服务设计 §7.3 码表。

## 7. 存储与部署

- 元数据服务自有 PG database（如 `mask_metadata`），可与策略服务共用 PG 实例、两个库；直接 PG 不走 H2（与策略服务同款决策）；
- `mask-metadata.jar` 独立启动，默认 **8082**；API Key、PG 连接走环境变量；`password_ref` 指向的环境变量由部署侧注入（compose `environment` / K8s Secret）；
- 交付：`mvn package` 产出三个 fat jar；可选 docker-compose 一键起全套（metadata + policy + 单 PG 实例双库）。

## 8. 测试策略

1. **core 回归零变化**：introspect 留在 core，CLI `--pull-metadata`、`/api/metadata/pull` 及全部现有单测 + golden 原样通过——拆分正确性的首要证据；
2. **mask-metadata 单测**：实例 CRUD 与 name/dialect 不可变约束；写时类型校验（dialect TypeResolver 三引擎矩阵）；采集覆盖的事务性与失败不覆盖；密码引用解析（变量未设置 → `METADATA_CREDENTIAL_UNAVAILABLE`，错误不泄 URL）；YAML 导入（`rowFilter` 拒绝 + 指路）；无连接信息的实例 collect → 400；`metadata_version` 同事务递增；
3. **数据面契约测试**：`/api/metadata/instances/{name}` 产物反序列化为 core 配置模型后可被现有管线消费（类型同源验证）；
4. **mask-policy 改造测试**：`MetadataClient`（MockWebServer：拉取、版本轮询、变化触发重编译与 `configVersion` 传导、不可达 stale 复用、重启无缓存拒绝、404 保留缓存）；策略写入校验改走元数据缓存；编译 golden 与既有产物对齐；
5. **端到端**：compose 起三件套 → 导入建实例 → collect/导入表结构 → 策略服务建策略 → `POST /api/rewrite` 传 `instance` 成功；停元数据服务 → 改写继续可用（stale）；元数据侧改表结构 → 轮询传导到改写产物。

## 9. 对已定稿策略服务设计的修订清单

| 策略服务设计原文 | 修订为 |
|---|---|
| §3.1 实例（name、dialect、表结构）存策略服务 | 实例整体迁元数据服务；策略服务仅存 `policy.instance_name` |
| §4.1 `GET/POST /api/instances` 等实例端点 | 迁往元数据服务（§4.1）；策略服务删除这些端点 |
| §4.1 `POST /api/instances` 接受 `metadataYaml` 一把全收 | 拆两步：元数据服务 `/api/instances/import`（tables 段）+ 策略服务 `POST /api/instances/{name}/policies/import`（policies/columns 段） |
| §6.2 `policy_instance`/`instance_table`/`instance_column` 三张表 | 策略服务只留 `policy`（挂 instance_name）；实例三表由元数据服务 `meta_*` 三张表替代 |
| §7.2 写入校验（引用表/列存在）本地查库 | 改查 `MetadataClient` 缓存（miss 现拉；不可达且无缓存 → 503 拒绝） |
| §7.3 错误码 | 新增 `METADATA_SERVICE_UNAVAILABLE`（策略服务侧 503） |
| §5.1 改写引擎接入 | 不变（`/api/effective` 契约与缓存语义原样） |

策略微服务 10 任务实现计划（`2026-09-06-policy-service.md`）中受影响任务在写本设计的实现计划时一并修订。

## 10. 自评审记录（2026-09-07）

- 占位符：无 TBD/TODO 残留；
- 一致性：`/api/effective` 契约不变与"生效配置内含表结构"已对齐；导入器两步拆分与 rowFilter 拒绝规则贯通 §4.1/§5；版本传导（metadataVersion → configVersion）两级口径一致；
- 歧义：连接与采集口径字段整组可空（YAML 导入实例无连接信息，collect → 400）已显式写明；密码引用可回显（变量名）与密码不回显的边界已写死；
- 范围：单一 spec 可承载；实现按「mask-metadata 模块 → 策略服务改造 → 端到端」排序，plans 阶段展开。
