# 策略微服务设计方案（参考 Apache Ranger 策略模型）

日期：2026-09-06
状态：设计已与用户逐段确认（数据模型 / API / 引擎接入 / 存储部署 / 错误安全 五段全部通过）
前置文档：`2026-09-06-multi-dialect-design.md`（方言 SPI，本设计在其之上）

## 1. 背景与目标

sql-mask 当前形态：脱敏策略（`policy` 包 + YAML 配置）内嵌在改写服务中，随每个
`POST /api/rewrite` 请求传入完整 YAML。改写产出"最外层调用脱敏 UDF"的 SQL，
UDF 必须预先安装在目标引擎；改写工具不连接引擎、不执行 SQL。

本次目标：把策略部分拆分为**独立策略微服务**（配置仓库型），达成：

1. **运行时解耦**：改写引擎按实例名拉取生效配置，不再随请求传 YAML；
2. **便于扩展**：新增策略类型（如权限控制）只扩展策略模型，不动改写引擎；
3. **策略能力可复用**：策略以存储态（选择器原貌）对其他系统开放；
4. **跨引擎 UDF**：策略从属于具体引擎实例，同一个脱敏目的在不同引擎实例下
   各自配置该引擎的 UDF 名与参数，无需"默认+覆盖"机制。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 参照模型 | Apache Ranger 策略模型（ServiceDef/Service/Policy 三层、policyType、isEnabled、资源选择器、版本轮询下载） |
| API 定位 | 配置仓库型：核心端点"按实例名返回已编译的生效配置"，方言差异由策略服务吸收 |
| UDF/引擎差异 | 引擎实例独立配置；每个实例可配多条策略，每条可开关生效（isEnabled） |
| 方案 | 方案 C：Ranger 骨架 + 精简策略项——三层保留，选择器第一版只做精确值列表，不做通配；不引入用户/组维度 |
| 管理 UI | 第一版无 UI，纯 REST（用户未答复，按推荐默认执行，可推翻） |
| 鉴权 | 静态 API Key（管理面与数据面可用不同 Key）（用户未答复，按推荐默认执行，可推翻） |

### 1.2 明确不做（YAGNI）

- 资源选择器的通配符/正则/排除语义（第一版仅精确值列表）；
- users/groups/roles 维度（为将来 access 策略类型预留模型位，不实现）；
- 策略优先级与运行时冲突消解（同列多命中在配置期拒绝，见 §3）；
- 策略服务管理页面；
- 审计日志（仅留 created/updated 时间戳与 config_version）;
- 推送式配置分发（轮询 + 手动刷新即可）。

## 2. 概念映射（Ranger → 本项目）

| Ranger | 本项目 | 说明 |
|---|---|---|
| ServiceDef | EngineDef（内置，非数据） | 由现有 `DialectProfile` 投影：资源层级 catalog/schema/table/column、类型集、写入语句形态。第一版为服务端内置代码，加引擎=加一个 profile |
| Service | 引擎实例 Instance | 一个引擎的一个库（如 `pg_prod`）；策略挂实例上；"不同引擎独立配置"落在这一层 |
| DATAMASK Policy | 脱敏策略（policyType=datamask） | UDF 名 + 有序参数 + isEnabled |
| ROW_FILTER Policy | 行过滤策略（policyType=row_filter） | filterExpr；现有表声明 `rowFilter` 字段升格为策略 |
| 资源选择器 | 策略上的 resource | 取代现有 `columns:` 逐列绑定段，语义等价、归属反转 |
| 按版本下载 | 改写引擎按实例名轮询版本 + 拉取 | 配置仓库型数据面 |
| users/groups/roles | 第一版不做 | policyType 为字符串枚举，将来加 access 类型 + 策略项挂用户组，不动骨架 |

## 3. 数据模型

```
EngineDef（内置，非数据）
   └── 引擎实例 Instance          ← 一个引擎的一个库，如 pg_prod / trino_analytics
         ├── 元数据（表结构）       ← catalog/schema/table/列+类型（该引擎的类型名，存原始文本）
         └── Policy（N 条）        ← 每条独立、可开关
```

### 3.1 引擎实例

- `name`：唯一、创建后不可变（改写引擎引用的键）；
- `dialect`：postgresql / trino / mysql（指向内置 EngineDef）；
- 表结构元数据：catalog/schema/table/columns，列类型存该引擎的类型名原始文本
  （与多方言设计"YAML 类型名跟随引擎"一致）。表结构分引擎维护；
- 实例级开关第一版不做。

### 3.2 策略

datamask 示例：

```yaml
name: phone_mask
policyType: datamask
isEnabled: true
resource:
  catalog: crm
  schema: public
  table: customer
  columns: [phone, fax]     # 精确值列表，一列或多列共享此策略
udf: mask_phone             # UDF 按实例配置——跨引擎差异天然消解
arguments: [3, 4]
```

row_filter 示例：

```yaml
name: customer_active_only
policyType: row_filter
isEnabled: true
resource:
  catalog: crm
  schema: public
  table: customer           # 行过滤指向表，无 columns
filterExpr: "status = 'active'"
```

### 3.3 与现状的关键差异（4 条）

1. **`rowFilter` 从表声明升格为策略**：表声明不再有 `rowFilter` 字段，行过滤与
   脱敏统一为"策略 + 开关"模型。现有白名单校验逻辑原样复用，且双层校验：
   策略服务配置期校验一次，改写引擎管线内保留权威校验（fail-closed，不信任上游）。
2. **`columns` 逐列绑定 → 策略资源选择器**：列挂在策略上（值列表），不再有独立
   的 `columns:` 绑定段。
3. **同列多策略命中在配置期拒绝**：创建/更新策略时，若其选择器与该实例其他
   **已启用**策略重叠（同一列两条 datamask、或同一表两条 row_filter），直接报错。
   想换策略 = 禁用旧的启用新的。该约束取代优先级/首匹配机制，第一版无 priority 字段。
   重叠检测在服务层内存做（策略量级小）。
4. **每实例一个单调递增 `configVersion`**：任何元数据或策略变更在同一事务内 +1，
   供改写引擎轻量轮询。

### 3.4 权限控制扩展位

`policyType` 为字符串枚举；将来新增 `access` 类型 + 策略项挂 users/groups，
数据模型骨架不变。

## 4. API 设计

### 4.1 管理面（管理员/控制台/其他系统）

| 端点 | 说明 |
|---|---|
| `GET/POST /api/instances` | 列表 / 创建（name、dialect、初始表结构） |
| `GET/PUT/DELETE /api/instances/{name}` | 详情（含表结构全量）/ 更新元数据 / 删除（存在策略时拒绝删除，需先删策略） |
| `GET/POST /api/instances/{name}/policies` | 策略列表 / 创建 |
| `PUT/DELETE /api/instances/{name}/policies/{policyName}` | 更新（含 isEnabled 切换）/ 删除 |

- `name` / `dialect` 创建后不可变；
- 创建/更新的服务端校验即"实时校验"：类型名按该实例方言解析（复用方言
  TypeResolver）、`filterExpr` 走现有白名单校验、选择器重叠检测、引用的表/列必须
  已声明（含反向：更新元数据删表/删列时若被启用策略引用则拒绝并列出引用者）；
- **YAML 导入器**：`POST /api/instances` 额外接受 `metadataYaml`（现 YAML 格式），
  复用现有解析校验器：表结构直接建实例（表声明中的 `rowFilter` 字段转为
  row_filter 策略实体，表声明本身不再携带该字段）；旧 `columns` 绑定按 policy 名
  聚合反转成"每条策略一个列选择器"，旧 `policies` 段变成 datamask 策略实体
  （默认 enabled）。存量配置一键迁入。

### 4.2 数据面（改写引擎等消费方）

- `GET /api/effective/{instanceName}` —— 返回**已编译**的生效配置：

```json
{
  "instance": "pg_prod",
  "dialect": "postgresql",
  "configVersion": 42,
  "policySummary": { "enabled": 5, "disabled": 1 },
  "config": {
    "metadata": { "tables": [ { "catalog": "crm", "schema": "public",
                  "name": "customer", "rowFilter": "status = 'active'", "columns": [ ... ] } ] },
    "columns":  [ { "catalog": "crm", "schema": "public", "table": "customer",
                    "column": "phone", "policy": "phone_mask" } ],
    "policies": { "phone_mask": { "udf": "mask_phone", "arguments": [3, 4] } }
  }
}
```

- `GET /api/effective/{instanceName}/version` —— 轻量版本号，供消费方轮询缓存。

**编译职责收敛在策略服务**：存储态的选择器展开成逐列 `columns` 绑定、row_filter
策略编译回表声明的 `rowFilter` 字段、禁用策略剔除。产物即现有 YAML 的等价
JSON——改写引擎现有管线零改动接入。需要存储态（选择器原貌）的其他系统用管理面
`GET /api/instances/{name}`。

## 5. 改写引擎接入

### 5.1 配置来源抽象

新增 `PolicyConfigProvider` 接口，两个实现：

- `InlineYamlConfigSource`：现状内联 YAML，原样保留（向后兼容 + 测试友好）；
- `PolicyServiceConfigSource`：按实例名从策略服务取编译配置。

`POST /api/rewrite` 请求二选一：`metadataYaml` 或 `instance` 字段，同时给/都不给
→ `CONFIG_ERROR`。CLI 对应 `--instance` + `--policy-service`（或环境变量），与
`--metadata` 互斥。

### 5.2 缓存与版本（Ranger 插件式）

- 进程内缓存「实例名 → (configVersion, 编译配置)」，默认每 30s 轮询 `/version`
  （可配），版本变化才拉全量；
- 策略服务短暂不可用时**继续用缓存内当前版本改写**（stale-but-available，与
  Ranger 同款语义）；
- **绝不 fail-open**：进程重启后缓存为空且策略服务不可达 → 返回
  `POLICY_SERVICE_UNAVAILABLE`，绝不降级成原样输出 SQL；
- 紧急止血：轮询间隔可调小 + **改写服务**手动刷新端点 `POST /admin/cache/refresh`
  （清空并重拉指定实例的缓存；该端点在改写服务上，不属于策略服务）；
  不做推送机制。

```
策略微服务                    改写服务（现有 + 扩展）
┌──────────────┐   30s轮询   ┌─────────────────────────┐
│ 管理/存储/编译  │ ←────────── │ PolicyConfigProvider     │
│  configVersion │  版本变化时  │  → 版本缓存               │
└──────────────┘   拉全量     │  → RewriteEngine（不变）  │
                              └─────────────────────────┘
```

改写引擎对策略服务只有"版本轮询 + 拉取"两个依赖点，`RewriteEngine` 管线一行不改。

## 6. 项目形态、存储与部署

### 6.1 同仓转 Maven 多模块

```
mask/
├── mask-core/          ← 现有全部代码原样迁入（解析/血缘/改写/方言/server/CLI）
├── mask-policy/        ← 新策略微服务（独立 Spring Boot fat jar）
└── pom.xml             ← parent，两个子模块
```

- 依赖方向：`mask-policy` → `mask-core`（复用 `LoadedConfig` 配置模型、
  `YamlConfigLoader` 解析校验、方言 TypeResolver）；core 对策略服务零依赖，
  只持有 `PolicyConfigProvider` 接口和它的 HTTP 实现；
- 编译产物 DTO 即 core 的配置模型，类型同源不漂移；
- 迁移为机械工作（pom 拆分 + 包不动），现有测试全部跟随 core 模块。

### 6.2 存储：PostgreSQL

写少读多、数据量小，但配置数据要事务与约束——直接上 PG，避免先 H2 后迁移的返工。
四张表：

- `policy_instance`（name 唯一、dialect、config_version、时间戳）
- `instance_table` / `instance_column`（表结构与列，类型存原始文本）
- `policy`（instance_id、name、policy_type、is_enabled、udf、arguments、
  filter_expr、resource 选择器存 JSONB）

任何变更在同一事务内 `config_version + 1`。

### 6.3 部署

- `mask-policy.jar` 独立启动（默认 8081），API Key 走环境变量；
- 改写服务配 `policy.service.url` + `policy.service.apiKey` 两项即可接入；
- 交付：`mvn package` 产出两个 jar + 可选 docker-compose（policy + postgres）
  供本地起全套。

### 6.4 并发

管理操作走 PG 行锁 + 单事务（变更与 `config_version+1` 原子）；数据面编译在单
事务快照内读，产物自洽；版本号单调递增保证轮询不丢更新。

## 7. 错误与安全语义

### 7.1 禁用策略的显式语义（最重要）

`isEnabled=false` 的 datamask 策略在编译时剔除，对应列变为"无策略"→ **原样输出、
不脱敏**。这是"开关生效"要求的直接后果，是管理员的有意行为而非静默失败，文档
明确写死。`GET /api/effective` 响应带 `policySummary {enabled, disabled}` 元信息
字段供监控告警（不改 config 本体）。

### 7.2 编译期自洽保证（悬空引用前置到配置期）

策略服务在每次写入时做对称校验，保证数据面产物永远自洽：

- 策略引用的表/列必须已在实例元数据中声明；
- 反向：更新实例元数据删表/删列时，若被启用策略引用 → 拒绝并列出引用者；
- 选择器重叠、类型名非法（带方言上下文）、`filterExpr` 白名单违反 → 400 +
  路径化诊断。

改写引擎现有校验全部保留作为第二道防线（fail-closed，不信任上游）。

### 7.3 错误码汇总

| 场景 | 码 |
|---|---|
| 实例不存在 | `POLICY_INSTANCE_NOT_FOUND`（404） |
| 策略服务不可达且无缓存 | `POLICY_SERVICE_UNAVAILABLE`（改写侧） |
| API Key 缺失/错误 | 401 |
| 管理面校验失败 | 400 + `{code, message, details[]}` |
| 策略服务内部错误 | 500 `INTERNAL_ERROR`（编译为纯内存操作，理论不失败；若触发说明内部不一致） |

## 8. 测试策略

1. **core 回归零变化**：模块拆分后现有全部单测 + TPC-DS golden 保持通过，作为
   拆分正确性的首要证据；
2. **策略服务单测**：实例/策略 CRUD、配置期校验（重叠拒绝、悬空引用、类型按
   方言解析、filterExpr 白名单）、YAML 导入器（含旧 columns 聚合反转）；
3. **编译器单测**：选择器展开、row_filter 回填表声明、禁用剔除、policySummary；
4. **数据面契约测试**：编译产物反序列化为 `LoadedConfig` 后能被现有改写管线消费
   （策略服务 + core 配置模型类型同源的验证）；
5. **改写引擎接入测试**：`PolicyServiceConfigSource` 用 MockWebServer/内嵌桩验证
   拉取、版本轮询、缓存复用、不可达时 stale 复用与无缓存时
   `POLICY_SERVICE_UNAVAILABLE`、二选一参数校验；
6. **端到端**：本地起策略服务 + PG（docker-compose），建实例 → 导入 YAML →
   建策略 → `/api/rewrite` 传 `instance` 改写成功；切换 isEnabled 后轮询生效。

## 9. 自评审记录（2026-09-06）

- 占位符：无 TBD/TODO 残留；
- 一致性：编译产物含 `rowFilter`（由 row_filter 策略回填）与存储态表声明无该字段
  两处已对齐；`/admin/cache/refresh` 已标注属于改写服务；
- 歧义：YAML 导入器补齐"旧表声明 `rowFilter` → row_filter 策略"的转换规则；
- 范围：单一 spec 可承载，实现计划按「模块拆分 → 策略微服务 → 引擎接入」三阶段
  排序；无二义性需求残留。
