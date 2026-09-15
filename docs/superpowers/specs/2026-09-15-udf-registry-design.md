# UDF 注册表设计方案（对齐 PostgreSQL 函数模型）

日期：2026-09-15
状态：设计已与用户逐项确认（方向 / 交付 / 作用域 / 校验深度 / 建模粒度 / 模型+API / 校验+存储 七项全部通过）
前置文档：`2026-09-06-policy-service-design.md`（策略微服务，本设计在其模型之上）

## 1. 背景与目标

策略模型中 DATAMASK 策略的 `udf` 字段目前只是不透明字符串：写入时
`PolicyValidator` 仅检查非空与参数是标量，策略引用不存在的 UDF、参数个数或
类型与引擎里真实函数不匹配，都要到目标引擎执行改写后的 SQL 才暴露。改写链路
靠 `UnknownFunctionTable` 兜底（任意参数、返回类型≈首参类型），属于必要的
宽松，无法用于配置期把关。

本次目标：在策略子系统内新增**实例级 UDF 注册表**——按引擎实际安装的脱敏
函数，在项目内声明其签名（名称、有序参数类型、返回类型），提供 REST CRUD，
并让 DATAMASK 策略在写入时按签名做基本校验。UDF 仍预先安装在目标引擎，
本设计不生成 DDL、不连接引擎、不执行 SQL。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 方向 | 项目内 UDF 注册表 + 配置期校验（非 DDL 生成、非引擎拉取、非改写期介入） |
| 交付 | REST CRUD，策略服务的第一批管理端点，托管在 mask-core web 应用 |
| 作用域 | 挂引擎实例（instance）下，沿用"策略从属于实例"的既有决策 |
| 校验深度 | 仅配置期：策略写入时按签名校验；改写链路与 `UnknownFunctionTable` 不动 |
| 建模粒度 | 按函数名建模，重载是定义内的签名列表（REST 按名 CRUD） |
| 方言 | 类型声明经实例方言 TypeResolver 解析，机制三方言同构；本期测试与文档聚焦 PostgreSQL |
| 鉴权 | 本期不加（policy spec 已将 API Key 定为部署层职责，与 mask-core 现状一致） |

### 1.2 明确不做（YAGNI）

- 不生成 `CREATE FUNCTION` DDL，不向引擎部署或执行任何 SQL；
- 不从引擎 `pg_proc` 拉取函数（自录签名，非 introspection）；
- 不把签名编入生效配置流向改写引擎（返回类型推断仍走现状首参启发式；为将来扩展预留，见 §8）；
- 不建模 PG 的参数名、IN/OUT/VARIADIC 模式、默认值、SETOF、anyelement 多态；
- 不做隐式类型转换的解析规则（精确匹配 + 重载，与"工具不插类型转换"的既有立场一致）；
- 不动 YAML/CLI 路径：`metadata.yaml` / `policies.yaml` 中的 udf 仍是不透明名字。

## 2. 数据模型

```
EngineInstance（已有：name + dialect + 表列元数据）
  └── UdfDefinition（新增，mask-core policyserver/model）
        name: String                ← 沿用 NAME 正则 [A-Za-z0-9_.\-]+
        signatures: [UdfSignature]
            params:  List<String>   ← 有序位置参数的类型声明（实例方言的类型名）
            returns: String         ← 返回类型声明
```

- **首参约定**：`params[0]` 绑定被脱敏列的值，`params[1..]` 按序对应策略
  `arguments`（string/number/boolean 标量，见 §4.2）。与 README「包装目标
  类型」语义一致：工具不插类型转换，bigint 列需要 `mask_phone(bigint, …)`
  重载——注册表让这个坑在配置期暴露。
- **重载**：同 name 下多个签名，以 params 列表判等（对齐 PG 以
  「名字 + 参数类型列表」标识函数）；同 name 同 params 判重拒绝。
- **类型声明**：经实例方言 `TypeResolver`（与列类型解析同一套机制）解析并
  判等。PG 实例接受 postgres 类型集（`varchar` / `integer` / `numeric(p,s)`
  等），非法类型名在创建 UDF 时拒绝。

## 3. REST API

挂在 mask-core web 应用（策略服务的第一批管理端点）：

```
POST   /api/instances/{instance}/udfs          创建（name + 全部 signatures）
GET    /api/instances/{instance}/udfs          列出全部定义
GET    /api/instances/{instance}/udfs/{name}   单个
PUT    /api/instances/{instance}/udfs/{name}   整体替换签名列表
DELETE /api/instances/{instance}/udfs/{name}   删除（有启用中策略引用时拒绝）
```

- 错误契约沿用现状：未知实例 → `POLICY_INSTANCE_NOT_FOUND`；重复创建、
  删除不存在 → `CONFIG_ERROR`；
- 每次变更推进实例 `config_version`（现有 store 契约"每次变更必推进"）。

## 4. 校验规则（全部配置期，入口在 PolicyValidator）

### 4.1 UDF 写入时（create / replace）

- name 匹配 NAME 正则；signatures 非空，每个签名 params 非空（至少首参）；
- 每个类型声明经实例方言 TypeResolver 解析，非法类型名拒绝；
- 同 name 下 params 相同的签名判重拒绝。

### 4.2 DATAMASK 策略写入/更新时（按序过滤，错误信息给出原因与期望签名）

1. **存在性**：udf 名在实例注册表找不到 →
   `CONFIG_ERROR "unknown udf 'mask_phone' in instance 'pg_prod'"`；
2. **参数个数**：候选签名 = `params.size() == arguments.size() + 1`；
3. **标量类型矩阵**（严格，无跨族隐式转换）：

   | arguments 元素 | 兼容的参数类型 |
   |---|---|
   | number | integer / smallint / bigint / real / double precision / numeric |
   | string | varchar / char / text |
   | boolean | boolean |

4. **列类型匹配**：策略选中的每列，其经 TypeResolver 解析的类型必须与某候选
   签名 `params[0]` **精确相等**；失败时点名列与类型，如
   `column 'phone' (bigint) has no matching mask_phone overload`。
   不同列可命中不同重载——按调用点解析，与 PG 一致。

### 4.3 一致性守恒（by construction）

- 删除 UDF 或替换签名列表，若会使**启用中**的策略引用失效（名字消失，或
  原本匹配的签名全部不再匹配）→ 拒绝；禁用中的策略不受限（不参与编译）。
  延续 PolicyValidator「编译产物 by construction 自洽」的既有哲学。
- `EffectiveConfigResponse` 编译产物逐字节不变（配置期校验不进编译路径）。

## 5. 存储

- `schema.sql` 新表：

```sql
CREATE TABLE IF NOT EXISTS instance_udf (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  param_types TEXT NOT NULL,   -- JSON 数组文本（如 ["varchar","integer"]），TEXT 以支持 UNIQUE
  return_type VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, name, param_types)
);
```

- `PolicyStore` 接口扩展：`createUdf / replaceUdf / findUdf / listUdfs /
  deleteUdf`；`JdbcPolicyStore` 与 `InMemoryPolicyStore` 双实现，行为一致。
- `PolicyService` 相应扩展，UDF 变更与策略变更同样推进 `config_version`。

## 6. 影响面与不变量

| 路径 | 影响 |
|---|---|
| policy server 写路径（instance/policy/udf） | 新增 UDF CRUD；策略校验增强 |
| YAML/CLI（metadata.yaml、policies.yaml） | 完全不动 |
| 改写引擎（UnknownFunctionTable、返回类型启发式） | 完全不动 |
| 生效配置编译（EffectiveConfigCompiler） | 完全不动，输出逐字节不变 |
| mask-metadata（metaserver） | 完全不动（其 meta_instance 是采集连接信息，另一概念） |

## 7. 测试

- Validator 矩阵单测：未知 udf / 个数不匹配 / 标量类型矩阵（含跨族拒绝）/
  列类型无重载 / 多列多类型各命中不同重载 / 判重与非法类型名；
- 一致性守恒：删 Udf 被 enabled 策略引用时拒绝，disabled 不受限；
- Store 双实现一致性 + config_version 推进；
- REST 端点 MockMvc 测试（沿 `PolicyEndpointTest` 模式）；
- 编译回归：注册 UDF 后 effective config 输出不变。

## 8. 开放问题与后续扩展

- **实例/策略的管理面 REST**：`PolicyService` 的 instance/policy CRUD 尚无
  HTTP 端点（本设计的 UDF 端点是第一个）。用户的完整流程「REST 创建 UDF →
  REST 创建用户、资源、策略」需要补齐 instance/policy CRUD REST，属另立
  任务，不在本设计范围；
- **签名流向改写引擎**：将来若要让已知 UDF 用真实返回类型替代首参启发式，
  需把签名编入生效配置并接入 Calcite operator table，本设计已在模型上预留
  （签名与实例同存，编译路径未动）；
- **多方言文档与测试**：mysql / trino 实例机制同构，本期聚焦 PG，后续按需
  补测试样例。
