# 策略服务管理面 REST 设计方案（实例/策略 CRUD + 主体维度 + metaserver 导入 + API Key）

日期：2026-09-16
状态：已实现（见 docs/superpowers/plans/2026-09-16-policy-admin-rest.md）
前置文档：`2026-09-06-policy-service-design.md`（策略微服务模型）、
`2026-09-15-udf-registry-design.md`（UDF 注册表，已实现，其 REST 是管理面第一个端点）

## 1. 背景与目标

策略微服务的服务层（`PolicyService`：实例/表列 CRUD、策略 CRUD、生效配置编译）
已经完整，UDF 注册表补上了 UDF 签名的 REST 与配置期校验，但**实例与策略仍无
HTTP 面**——用户设想的完整流程「REST 建用户、资源（库表列）、策略（选列+选
UDF）」还差最后一块。同时，策略服务的策略模型没有主体维度（对实例内所有人无
差别生效），而 `policies.yaml` 路径（mask-policy 模块）早已有完整的主体语义
（`SubjectSelector(users/groups)`、`*` 通配、按请求主体匹配）——两套体系能力
不对齐。

本次目标：

1. 补齐实例与策略的管理面 REST（CRUD），与已上线的 UDF REST 拼成完整流程；
2. **主体维度进策略服务**：策略携带 users/groups 选择器，生效配置按主体编译，
   策略服务获得与 YAML 路径同等的按人脱敏能力；
3. 提供「从 metaserver 导入表结构」端点，打通采集→策略的流水线；
4. 管理面与数据面加静态 API Key 鉴权。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 用户定位 | 主体维度直接进策略（PolicyEntity + subjects）；**不建独立用户目录**，主体是策略上的自由字符串（与 YAML 路径一致） |
| 资源来源 | 手工 REST CRUD + 从 metaserver 导入（复用 `MetadataClient`） |
| 鉴权 | 静态 API Key：`SQLMASK_ADMIN_API_KEY`（管理面）/`SQLMASK_DATA_API_KEY`（数据面），未配置则不拦截；401 形状对齐 metaserver |
| 主体机制 | 方案 A：服务端按主体参数编译（`?user=&groups=`），客户端按主体缓存；不改为"下发原始策略" |
| 无主体参数语义 | 匿名主体（仅命中 `*` 策略）；存量策略迁移回填 `{"users":["*"]}` 保持"全体生效"现状 |
| 幂等（导入） | 重复导入相同结构也推进 config_version（沿用 updateInstanceTables 语义） |

### 1.2 明确不做（YAGNI）

- 不建独立用户/组目录 CRUD，不做用户存在性校验；
- 不做策略优先级、通配资源选择器（沿用 policy-service spec 的既有 YAGNI）；
- 不做 `/api/rewrite` 等业务端点的鉴权（维持现状，另行立项）；
- 不做管理 UI（纯 REST，沿用 policy-service spec 决策）；
- 不动 `policies.yaml` 本地路径与 `UnknownFunctionTable`、`EffectiveConfigCompiler`
  的逐主体编译产物形状。

## 2. 数据模型

```
EngineInstance（不变：name + dialect + tables）
  ├── UdfDefinition（已上线，不动）
  └── PolicyEntity（扩展）
        + subjects: SubjectSelector        ← 复用 mask-policy 的 record：
                                             (Set<String> users, Set<String> groups)
```

- `SubjectSelector` 直接复用 `io.sqlmask.policy.model.SubjectSelector`（非空
  校验与 `*` 通配语义内建：users 或 groups 至少一项非空，`["*"]` 表示全体）；
- 存储：`policy` 表新增 `subjects JSONB`（允许短暂 NULL，读取层对 NULL 按
  `{"users":["*"]}` 容错）；存量行迁移回填 `{"users":["*"]}`（语义零变化：
  现状即全体生效）。`schema.sql` 的 `CREATE TABLE IF NOT EXISTS` 不会改
  已存在的表——存量部署执行 `ALTER TABLE policy ADD COLUMN subjects JSONB`
  + 回填 `UPDATE policy SET subjects = '{"users":["*"]}'::jsonb WHERE subjects IS NULL`；
- REST payload（datamask 示例）：

```json
{
  "name": "phone_mask_analysts",
  "policyType": "datamask",
  "isEnabled": true,
  "resource": {"catalog": "crm", "schema": "public", "table": "customer",
               "columns": ["phone"]},
  "subjects": {"users": ["alice"], "groups": ["analysts"]},
  "udf": "mask_phone",
  "arguments": [3, 4]
}
```

## 3. REST API（管理面）

新建 `PolicyAdminController`（mask-core `io.sqlmask.server`）；DTO 映射后全部
走既有 `PolicyService` 服务面（校验、守恒守卫、版本推进免费获得）：

```
POST   /api/instances                        {name, dialect, tables[]}
GET    /api/instances                        列表
GET    /api/instances/{name}                 单个
PUT    /api/instances/{name}/tables          整体替换表列（启用策略引用不断）
DELETE /api/instances/{name}                 有策略时拒绝（现有契约）

POST   /api/instances/{name}/policies        创建（含 subjects）
GET    /api/instances/{name}/policies        列表
GET    /api/instances/{name}/policies/{policy}
PUT    /api/instances/{name}/policies/{policy}    名字不可变（现有契约）
DELETE /api/instances/{name}/policies/{policy}

POST   /api/instances/{name}/import-metadata      见 §6

# UDF（已上线）：POST/GET/PUT/DELETE /api/instances/{name}/udfs[/{name}]，不动
```

错误契约沿用：`CONFIG_ERROR` / `POLICY_INSTANCE_NOT_FOUND` → 400 `{code,
message}`；实例内策略名唯一、UDF 引用校验（四步解析）照常生效。

## 4. 校验规则变化（PolicyValidator）

- **subjects 非空**：`SubjectSelector` 构造即校验（users/groups 至少一项非空，
  `*` 用法与 YAML 路径一致）；
- **重叠判定按主体相交放宽**：同表同类型的两条启用策略，仅当
  **主体选择器可能命中同一主体** 且资源重叠（datamask 列相交 / row_filter
  同表）时拒绝。主体相交判定：

  > selectorA 与 selectorB 相交 ⟺ 任一方 users 或 groups 含 `*`，
  > 或 A.users ∩ B.users ≠ ∅，或 A.groups ∩ B.groups ≠ ∅，
  > 或（A.users ≠ ∅ 且 B.groups ≠ ∅），或（A.groups ≠ ∅ 且 B.users ≠ ∅）
  > （复合主体（user, groups）经 `SubjectSelector.matchLevel` 独立匹配
  > users 与 groups——任一方 users 非空且另一方 groups 非空，即可能存在
  > 同时命中两条策略的复合主体，按保守相交处理）

- 删除/替换 UDF 的守恒守卫同样按主体无关执行（引用完整性是实例级属性）；
- DATAMASK 的 UDF 四步校验（存在性/个数/标量矩阵/列类型）不变。

## 5. 数据面：按主体编译

- `GET /api/effective/{instance}?user=u&groups=a,b`（groups 逗号分隔）；
  **无参数 = 匿名主体**（仅命中 `*` 策略）。注：该端点在 policy-service spec
  中规划过但从未落地（只有客户端 `PolicyServiceConfigSource` 在拼这个 URL），
  本次随主体参数一并新建；
- `EffectiveConfigCompiler.compile(instance, policies, subject)`：先按
  `SubjectSelector.matches(subject)` 过滤启用策略，再走现有展开——每个主体
  得到自洽配置（一表一条 row_filter、列不重叠天然保持）；响应产物形状不变；
- `configVersion` 仍是实例级：任何策略/表/UDF 变更推进，客户端据此刷新；
- **客户端**（`PolicyServiceConfigSource`）：单缓存改为
  `Map<主体键, ResolvedConfig>`——`load(subject)` 缺失即拉取并缓存；
  `refresh()` 版本轮询时刷新全部已缓存主体；服务不可达时命中缓存 → stale
  可用、冷主体 → `POLICY_SERVICE_UNAVAILABLE` fail closed（语义不变）；
- **改写链路**：`RewriteController` 已组装 `Subject.of(user, groups)`；策略服务
  模式下按请求主体选配置（CLI `--user/--groups` 与 Web 请求字段照旧传入）。

## 6. metaserver 导入

- `POST /api/instances/{name}/import-metadata`，body：

```json
{"metadataBaseUrl": "http://metadata:8082", "metadataApiKey": "...",
 "metadataInstance": "pg_prod"}
```

- 复用 `MetadataClient.fetch(metadataInstance)` 获取
  `MetadataSnapshot(dialect, tables)`；
- 策略实例不存在 → 创建（dialect 取快照的 dialect）；已存在 → 整体替换表列
  （走现有守卫：启用策略引用的表/列不可消失）；类型照常过方言 `TypeResolver`；
- 错误传播沿用 `MetadataClient` 现有映射：源不可达 →
  `METADATA_SERVICE_UNAVAILABLE`，源实例不存在 → `METADATA_INSTANCE_NOT_FOUND`；
- 幂等：重复导入相同结构也推进 `config_version`（沿用 `updateInstanceTables`
  语义，文档注明）。

## 7. 鉴权（ApiKeyFilter）

- 抄 metaserver 的 `ApiKeyFilter` 模式，mask-core 新增一个：
  - 保护范围：`/api/instances/**`（管理面，校验 `SQLMASK_ADMIN_API_KEY`）与
    `/api/effective/**`（数据面，校验 `SQLMASK_DATA_API_KEY`）；
  - 环境变量未配置 → 不拦截（本地开发友好）；
  - 401 响应对齐 metaserver 形状：
    `{"code":"UNAUTHORIZED","message":"missing or invalid API key","details":[]}`；
- `/api/rewrite`、`/api/metadata`、`/api/config`、`/api/policies` 等业务端点
  本期不纳入（维持现状）；
- `PolicyServiceConfigSource` 已发送 `X-Api-Key` 并处理 401（现状即可对接）。

## 8. 影响面与不变量

| 路径 | 影响 |
|---|---|
| policy server（模型/校验/编译/REST） | subjects 进模型与校验；effective 带主体参数；新增实例/策略/导入端点与鉴权 |
| 客户端（PolicyServiceConfigSource） | 缓存结构改 Map；拉取带主体参数 |
| YAML/CLI 路径（metadata.yaml、policies.yaml） | 完全不动 |
| 改写引擎（UnknownFunctionTable、返回类型启发式） | 完全不动 |
| mask-metadata（metaserver） | 完全不动（只作为导入数据源被调用） |
| UDF REST（已上线） | 不动（纳入 ADMIN 鉴权范围） |

## 9. 测试

- Validator 主体相交矩阵：`*`×任意相交、users 交、groups 交、完全不相交放行
  （同表同类型不再误拒）；
- Compiler 主体过滤：匿名仅命中 `*`、user 精确命中、group 命中、混合选择器；
- 客户端：多主体缓存互不串、版本变更全量刷新、冷主体 + 服务不可达 fail closed、
  stale-but-available 保持；
- REST MockMvc 全流程：建实例 → 注册 UDF → 建带主体策略 → 不同主体拉 effective
  得到不同配置；策略 CRUD 错误契约；导入（stub `MetadataClient`：创建/更新两分支
  + 源故障传播）；
- 鉴权：配置 Key 后无/错 Key → 401，未配置 → 放行；管理面/数据面 Key 分离；
- 存量兼容：无 subjects 的旧策略行读取时等价 `{"users":["*"]}`；
  `EffectiveConfigCompiler` 对全 `*` 存量输出的编译结果与升级前逐字段一致。

## 10. 开放问题与后续扩展

- 管理面 UI（浏览器页面编排「建实例→导入→建 UDF→建策略」）：policy-service
  spec 已留为不做，待 REST 稳定后另立；
- `/api/rewrite` 业务面鉴权与多租户：另行立项；
- 主体到 access 策略类型（行级权限等）：模型位已留（policyType 枚举可扩展）。
