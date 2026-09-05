# SQL 脱敏改写 CLI 设计方案

## 1. 背景与目标

基于 Apache Calcite 实现一个 PostgreSQL SQL 脱敏改写 CLI。工具读取 YAML 中声明的表结构、列脱敏策略和 UDF 参数，将查询改写为“原始查询作为内层、最外层对结果列调用脱敏 UDF”的 SQL。工具只负责解析、校验、血缘分析和 SQL 输出，不连接查询引擎、不执行 SQL，也不实现脱敏函数；UDF 由目标查询引擎提供。

第一版目标是验证以下主链路：

```text
YAML + PostgreSQL SQL
  -> Calcite 解析和校验
  -> 最终输出列血缘分析
  -> 精确匹配列策略
  -> 最外层包装 UDF
  -> PostgreSQL SQL
```

## 2. MVP 范围

### 2.1 支持

- PostgreSQL 风格输入和输出 SQL；
- 一次输入多条 SQL，按语句独立处理并保持顺序；
- `SELECT` 和 `WITH ... SELECT`；
- `INSERT INTO ... SELECT`、`CREATE TABLE [IF NOT EXISTS] ... AS SELECT`：对写入数据
  脱敏（源查询整体包装），目标表名/目标列清单原样保留，目标表无需在 YAML 中声明；
  `INSERT INTO ... VALUES`（纯字面量）原样直通，VALUES 中藏子查询直接失败；
- 普通、非递归 CTE；
- 子查询、派生表和多层普通 CTE 的最终输出血缘追踪；
- 输出表达式中的直接列和派生列；
- 一个派生表达式存在多个原始来源时，使用配置的策略选择规则；第一版未配置优先级时不保证来源顺序，具体选择规则由实现定义并保持稳定；
- 精确的 `catalog.schema.table.column` 策略匹配；
- 重复输出别名作为原始 SQL 的合法结果形态保留；
- 原始查询无策略的输出列原样输出；
- YAML 中声明的完整表结构用于 Calcite 解析、类型推导和血缘分析。

### 2.2 不支持

第一版遇到下列语句直接失败，不跳过、不原样输出：

- `UPDATE`、`DELETE` 及其他 DDL；
- `WITH RECURSIVE` 的完整血缘追踪；
- 无法解析的 PostgreSQL 特有语法；
- 无法唯一追踪的输出列来源。

写入语句的支持范围见 2.1；`MERGE`、`INSERT ... ON CONFLICT`、带有 `REPLACE/VOLATILE`
等方言变体的 `CREATE TABLE` 暂不支持，列入后续计划。

## 3. 核心改写语义

### 3.1 外层包装

原始查询必须作为内层查询整体保留，CTE、子查询、过滤、连接、聚合、排序、分页和集合操作均在内层完成。外层只包含结果投影，不增加 `WHERE`、`JOIN`、`GROUP BY`、`HAVING`、`ORDER BY` 或分页。

```sql
-- 输入
SELECT c.id, c.phone
FROM crm.public.customer AS c
WHERE c.phone = '13800138000'
ORDER BY c.id
LIMIT 10;
```

```sql
-- 输出示意
SELECT
    r.id,
    mask_phone(r.phone, 3, 4) AS phone
FROM (
    SELECT c.id, c.phone
    FROM crm.public.customer AS c
    WHERE c.phone = '13800138000'
    ORDER BY c.id
    LIMIT 10
) AS r;
```

语义为：先执行原始查询，再对最终结果中的 `phone` 调用 UDF。`WHERE` 使用底层明文列；`AS phone` 是结果列别名，不会修改底层表列。

### 3.2 输出列级脱敏

对原始查询根输出的每个表达式做血缘分析。只要来源列表中有配置了策略的基础列，就在外层对该输出列整体调用一次对应 UDF。没有匹配策略时直接引用该输出列。

```sql
-- 输入
SELECT lower(c.email) AS normalized_email
FROM crm.public.customer AS c;
```

```sql
-- 输出
SELECT
    mask_email(r.normalized_email) AS normalized_email
FROM (
    SELECT lower(c.email) AS normalized_email
    FROM crm.public.customer AS c
) AS r;
```

第一版不判断 UDF 是否适合派生结果或输出类型，遵循“查找到原始列且该列有策略就脱敏”的原则。例如 `count(phone)` 也可能按来源策略套用 UDF；类型和语义校验列入待办。

### 3.3 多来源策略

来源分析得到一个输出表达式依赖的基础列集合。第一版只判断集合中是否存在已配置策略；若存在一个或多个匹配策略，选择一个稳定的策略并在外层对完整输出列调用一次 UDF。第一版不依赖表达式首次出现顺序，也不承诺多个策略之间的业务优先级；后续通过策略优先级或显式选择规则决定多策略冲突。

```sql
SELECT concat(c.email, '-', c.phone) AS contact
FROM crm.public.customer AS c;
```

若 `email` 和 `phone` 均有策略，第一版只保证 `contact` 会被脱敏，不保证二者的业务优先级：

```sql
SELECT
    mask_email(r.contact) AS contact
FROM (
    SELECT concat(c.email, '-', c.phone) AS contact
    FROM crm.public.customer AS c
) AS r;
```

上例中的具体选择仅作为示意；第一版对多个命中策略按规范化 `ColumnKey` 的字典序选择，保证同一输入稳定但不表达业务优先级。后续优先级能力加入后替换该选择器。

### 3.4 无策略时原样输出

如果所有最终输出列都没有匹配策略，则不增加外层包装，直接输出原始查询：

```sql
SELECT c.id, c.name
FROM crm.public.customer AS c;
```

如果只有部分输出列命中策略，则仍增加外层包装，未命中的列在外层原样引用：

```sql
SELECT
    r.id,
    mask_phone(r.phone, 3, 4) AS phone
FROM (
    SELECT c.id, c.phone
    FROM crm.public.customer AS c
) AS r;
```

## 4. 原始列信息查找与血缘分析

策略匹配不依据输出别名，而依据最终输出表达式所引用的基础表列。实现上分为名称解析和血缘追踪两步：

1. 使用 YAML 构造 Calcite `SchemaPlus`、表和列类型；使用 `SqlValidator` 解析表名、表别名、列名、作用域和类型。
2. 将校验后的查询转换为 `RelNode`。从根 `LogicalProject` 的每个输出表达式开始，沿输入关系按字段 ordinal 解析来源；不要求保留表达式中来源列的首次出现顺序。
3. `RexInputRef` 沿输入关系向下解析：穿过 `Project` 时按字段 ordinal 展开对应投影表达式，穿过 `Filter`、`Sort`、`Aggregate`、`Join` 时沿输入映射继续追踪，抵达 `TableScan` 后得到完整的 `catalog.schema.table.column`。
4. CTE 和派生表按输出字段 ordinal 建立映射，再递归解析其定义；CTE 名称和表别名遵循当前 SQL 作用域，不使用全局名称猜测。
5. 将来源集合逐一查找策略索引 `ColumnKey -> MaskingPolicy`；没有命中则原样输出，命中一个或多个则选择一个稳定策略。

由于来源顺序不参与第一版策略选择，`RelMetadataQuery.getColumnOrigins` 可以作为主要来源分析能力。它返回某个关系输出列对应的基础列来源集合，正好满足当前“只要查找到原始列且配置了策略就脱敏”的规则。后续增加策略优先级或显式选择规则时，再扩展分析结果和策略选择器。仍需将 `null`（无法推断来源）与空集合（无基础列来源或无来源）区分：前者进入 `UNKNOWN` 并失败，后者可原样输出。对 CTE/派生表的字段 ordinal 映射、作用域和重复名称仍需结合 Calcite 验证结果及关系节点处理，不能仅凭输出名称查找。

来源分析结果至少包含：输出序号、输出名称、输出类型、原始列来源集合、选中策略和分析状态。

分析状态定义为：

```text
RESOLVED  -> 已追踪到一个或多个基础列，可匹配策略
NO_ORIGIN -> 表达式不依赖基础列，如常量，可原样输出
UNKNOWN   -> 无法安全追踪来源，第一版失败
```

示例：

```sql
WITH contact AS (
    SELECT concat(c.email, '-', c.phone) AS value
    FROM crm.public.customer AS c
)
SELECT value
FROM contact;
```

来源追踪结果为：

```text
contact.value -> customer.email、customer.phone -> 命中一个稳定策略
```

来源集合中存在策略时，外层对完整的 `value` 结果列调用选中的策略。第一版不依赖表达式中的来源出现顺序；找不到原始列定义时与找不到 SQL 列不同：SQL 列不存在或有歧义属于校验失败；列已解析但没有策略则原样输出；来源为 `UNKNOWN` 才按安全策略失败。

## 5. WITH、子查询与血缘

普通 CTE 内部不插入 UDF，但最终输出血缘必须沿 CTE 依赖链追踪到基础表列。

```sql
WITH active_customer AS (
    SELECT c.phone
    FROM crm.public.customer AS c
    WHERE c.status = 'ACTIVE'
)
SELECT phone
FROM active_customer
WHERE phone = '13800138000';
```

血缘：

```text
根 phone -> active_customer.phone -> customer.phone
```

输出结构：

```sql
SELECT
    mask_phone(r.phone) AS phone
FROM (
    WITH active_customer AS (
        SELECT c.phone
        FROM crm.public.customer AS c
        WHERE c.status = 'ACTIVE'
    )
    SELECT phone
    FROM active_customer
    WHERE phone = '13800138000'
) AS r;
```

CTE 的列名列表按列序号建立映射；CTE 名称、表名和别名按 SQL 作用域解析。递归 CTE 若不能在检测环的前提下确定来源，直接失败，不无限展开。

## 6. 输出列名与重复别名

不使用 PostgreSQL 专属的派生表列别名列表，也不统一修改内层 `SELECT list`。外层按内层结果列的原始名称引用，并恢复原始输出别名、顺序和大小写。

重复输出别名本身是合法 SQL，不作为失败条件：

```sql
SELECT c.phone AS value, c.email AS value
FROM crm.public.customer AS c;
```

但如果包装层只能看到两个同名字段，则无法可靠区分应脱敏的列。此问题记录为跨方言待办，不把它错误描述为原查询非法。

原查询中引用重复别名的情况由 Calcite validate 处理。例如：

```sql
SELECT c.phone AS value, c.email AS value
FROM crm.public.customer AS c
ORDER BY value;
```

Calcite 会报告 `value` 歧义；这是原查询本身无效，而不是包装器新增的错误。

后续按方言能力解决包装层列定位，例如 PostgreSQL 可考虑：

```sql
SELECT
    mask_phone(r.__mask_col_0) AS value,
    mask_email(r.__mask_col_1) AS value
FROM (
    SELECT c.phone AS value, c.email AS value
    FROM crm.public.customer AS c
) AS r (__mask_col_0, __mask_col_1);
```

其他方言必须调查等价机制；没有可靠机制时不能生成可能引用错误列的 SQL。

## 7. 系统组件与接口

第一版按职责拆分为以下组件：

```text
CLI
 ├── ConfigLoader              YAML 读取与配置校验
 ├── MetadataProvider          YAML 表结构 -> Calcite Schema
 ├── PolicyRegistry            ColumnKey -> MaskingPolicy
 ├── SqlParser/Validator       多语句拆分、PostgreSQL 解析和校验
 ├── LineageAnalyzer           根输出列 -> 基础表列来源
 ├── RewritePlanner            输出列与策略选择、包装计划
 └── SqlUnparser               PostgreSQL SQL 输出
```

建议的核心接口边界：

```text
MetadataProvider
  getTable(catalog, schema, table) -> TableMetadata

PolicyRegistry
  find(ColumnKey) -> Optional<MaskingPolicy>

LineageAnalyzer
  analyze(RelNode root) -> List<OutputLineage>

RewritePlanner
  plan(SqlNode original, List<OutputLineage>) -> RewritePlan

DialectAdapter
  parseConfig() / createValidator() / unparse()
  capabilities()
```

`LineageAnalyzer` 不负责选择 UDF；它只返回每个根输出字段的来源集合。`PolicySelector` 根据来源集合查询 `PolicyRegistry`，第一版多个策略命中时按规范化 `ColumnKey` 字典序选择，后续可替换为优先级选择器。`RewritePlanner` 只在至少一个输出字段命中策略时创建包装计划，否则返回原 SQL。

改写计划按输出位置保存，不使用输出名称作为唯一键：

```text
OutputRewrite
  ordinal: int
  outputName: Identifier
  sourceOrigins: Set<ColumnOrigin>
  policy: Optional<MaskingPolicy>
  wrapperReference: Identifier
```

这样可以保留输出顺序，并为重复别名留下方言特定的处理入口。

## 8. YAML 配置

第一版在 YAML 中声明所有 SQL 可能引用的完整表结构，同时单独声明列策略和策略定义：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - name: id
          type: bigint
        - name: phone
          type: varchar
        - name: email
          type: varchar
        - name: status
          type: varchar

columns:
  - catalog: crm
    schema: public
    table: customer
    column: phone
    policy: phone_mask
  - catalog: crm
    schema: public
    table: customer
    column: email
    policy: email_mask

policies:
  phone_mask:
    udf: mask_phone
    arguments: [3, 4]
  email_mask:
    udf: mask_email
    arguments: []
```

规则：

- `catalog`、`schema`、表名、列名和类型必填；
- SQL 引用的所有表必须在 `metadata.tables` 中声明；
- 列策略键严格使用 `catalog.schema.table.column`；
- 不支持省略限定名、`search_path`、通配符、正则或标签匹配；
- 同一策略可以绑定到不同类型的列；第一版不做类型校验；
- `arguments` 是按顺序排列的标量参数；命名参数和复杂参数类型后续再扩展；
- YAML 只描述配置和元数据，不连接数据库、不加载引擎、不执行 UDF；
- 第一版支持常见 PostgreSQL 标量类型（如 `boolean`、整数、浮点、`decimal`、`char`、`varchar`、`text`、`date`、`timestamp`）；类型只用于 Calcite 解析和推导，不参与策略选择；数组、JSON、复合类型和用户自定义类型列入待办；

### 8.1 元数据提供方式

第一版从 YAML 加载完整表结构，并构造 Calcite schema 和表对象。后续从查询引擎加载元数据时，只替换元数据提供者；策略索引和改写接口仍使用 `ColumnKey -> MaskingPolicy`。

### 8.2 标识符大小写

第一版遵循 PostgreSQL/Calcite 的标识符规则：未加双引号的标识符按配置的 Calcite lex/casing 规则规范化（PostgreSQL 默认折叠为小写），双引号标识符保留大小写。策略索引使用规范化后的 `catalog.schema.table.column`；大小写不同且语义不同的对象不能合并。

```yaml
columns:
  - catalog: crm
    schema: public
    table: customer
    column: phone
    policy: phone_mask
```

该策略匹配未加引号的引用：

```sql
SELECT phone FROM customer;
SELECT c.phone FROM customer AS c;
```

对于明确的大小写敏感对象，需要在 YAML 中使用与元数据一致的名称，并由 Calcite 按引用标识符规则解析：

```sql
SELECT "Phone" FROM customer;
```

如果 YAML 未声明对应的大小写敏感列，则列解析或策略匹配失败，不根据大小写猜测。

## 9. 处理流程

1. 加载并校验 YAML；建立 Calcite schema/table/column 和策略索引。
2. 将输入拆分为多条 SQL，忽略空语句。
3. 逐条解析并 validate；只接受 `SELECT` 或 `WITH ... SELECT`。
4. 转换为关系表达式，确定根查询输出字段和表达式。
5. 对每个根输出表达式使用列来源元数据分析，沿普通子查询和 CTE 追踪基础列；来源集合不保留表达式出现顺序。
6. 对来源集合查找精确策略；多个命中策略按规范化 `ColumnKey` 字典序选择一个，得到零个或一个选中策略。
7. 如果所有输出列都没有选中策略，直接保留原始查询；否则生成外层包装 SQL，每个命中列最多调用一次 UDF。
8. 使用 PostgreSQL 方言输出 SQL，保持语句顺序。
9. 任何一条语句失败则整次命令失败，不输出部分结果。

状态区分：

```text
可解析来源 + 无策略 -> 原样输出
可解析来源 + 有策略 -> 外层套 UDF
无法解析 SQL 或无法追踪来源 -> 失败
```

## 10. CLI

```bash
sql-mask --metadata metadata.yaml --sql "SELECT phone FROM customer;"
sql-mask --metadata metadata.yaml --input query.sql --output masked.sql
```

- `--sql` 和 `--input` 至少提供一个，输入内容可以包含多条语句；
- 未指定 `--output` 时改写 SQL 输出到 stdout；
- 错误输出到 stderr；
- 任意语句失败返回非零退出码；
- 工具不执行 SQL、不连接查询引擎。

## 11. 错误处理

以下情况必须失败：

- YAML 语法、结构或策略引用无效；
- SQL 不是允许的查询类型；
- 表未在 YAML 中声明；
- SQL 列不存在或列引用歧义；
- 输出列无法稳定枚举或无法追踪到基础来源；
- CTE 血缘出现无法处理的递归环；
- 目标方言无法安全表达所需的外层包装。

失败时输出包含语句位置、输出列（如可得）和原因的诊断信息，不生成部分改写结果。

## 12. 测试重点

- 单表直接列、表别名和完整限定名匹配；
- 无策略列与有策略列混合；
- `WHERE`、`JOIN`、聚合、排序、分页保持在内层；
- 多层子查询和普通多层 CTE 血缘；
- CTE 列名列表和 CTE/基础表同名作用域；
- 派生列单来源、多来源以及多来源的稳定策略选择；
- 重复输出别名；Calcite 对重复别名和 `ORDER BY` 歧义的 validate 行为；
- 多条 SQL 的顺序、错误和退出码；
- 未声明表、未知列、歧义列和无法追踪血缘的失败诊断；
- UDF 名称和有序标量参数输出。

## 13. 后续待办

1. ~~`CREATE TABLE AS SELECT`、`INSERT ... SELECT` 等写入语句~~ 已支持基础形式
   （见 2.1 与 §14.8）；`MERGE`、`INSERT ... ON CONFLICT`、写入目标列的策略匹配等待办仍在。
2. 多来源策略的优先级或显式选择规则。第一版按规范化 `ColumnKey` 字典序稳定选择。
3. 通配符、正则和数据标签匹配。
4. 从查询引擎加载表结构元数据。
4. UDF 输入/输出类型与签名校验。
5. 聚合、窗口函数、数值表达式等输出类型和语义判断。
6. 输出策略覆盖来源策略及多策略组合。
7. `SELECT *` 的完整跨表展开和复杂列血缘。
8. `WITH RECURSIVE` 的完整无环血缘分析。
9. 不同方言的派生表重复列名定位；PostgreSQL 的列别名列表、其他引擎等价语法或列序号能力。
10. 不支持派生表列别名列表的方言兼容策略，避免统一改名破坏 `ORDER BY` 等原始别名引用。
11. PostgreSQL 特殊顶层语法（如行锁）包装后的兼容性。
12. `WHERE`、`JOIN` 等非输出位置保护、查询参数/字面量保护。
13. 命名参数、复杂参数类型和 UDF 是否在目标引擎实现的检查。
14. 注释和原始格式保留。
15. 数组、JSON、复合类型和 PostgreSQL 用户自定义类型。
16. 多查询引擎方言 SPI：方言解析配置、标识符规则、SQL 输出及能力声明；第一版只实现 PostgreSQL。

## 14. 实现说明（与设计差异的记录）

实现过程中发现的 Calcite 行为，作为对上面设计的落地澄清：

1. **CTE 内联展开**：Calcite 把 CTE 引用转换为只带名字和行类型的
   transient scan，CTE body 不进入关系表达式树，`RelMetadataQuery.getColumnOrigins`
   只能看到 CTE 名字而非基础列。实现上在解析之后、校验之前，把普通 CTE
   在分析树中内联为 `AS` 派生表（保留 CTE 列名列表的重命名语义，按
   PostgreSQL 作用域做遮蔽），带自引用/环检测；输出用的原始解析树不变。
2. **validate 会原地改写解析树**：Calcite 校验过程会把 `ORDER BY`/`LIMIT`
   下推进 `SqlSelect`、插入隐式 `CAST` 等。因此原始 SQL 文本在校验前先行
   快照，作为包装层的内层查询，保证「内层为用户原始查询」。
3. **输出为 Calcite 生成的 SQL**：不保留注释和原始排版；`LIMIT n` 渲染为
   PostgreSQL 同样支持的 `FETCH NEXT n ROWS ONLY`；函数名输出 Calcite 规范
   形式（如 `COUNT(*)`）；标识符按需要加引号（保留字、大小写、特殊字符）。
4. **标识符与函数大小写**：表/列按 PostgreSQL 语义解析（未加引号折叠小写、
   引号保留大小写、比较大小写敏感）；函数名按大小写不敏感解析（与 PostgreSQL
   一致），Calcite 内置与 PostgreSQL 库函数之外补充了 varargs `concat`。
5. **列可空性**：YAML 不声明可空性，表列按可空注册（PostgreSQL 默认行为）。
   若注册为 NOT NULL，Calcite 会把 `count(col)` 优化为 `count(*)`，破坏
   `count(phone)` 等表达式的列来源分析。
6. **输出位置子查询**：输出表达式含标量子查询时，列来源元数据无法看入，
   视为 `UNKNOWN` 直接失败（避免把子查询读取的敏感列误判为无来源而漏脱敏）。
7. **引擎自定义函数（未知函数宽容解析）**：目标引擎的 UDF（如 `mask_idcard(c.phone,'abc')`）
   在 Calcite 中没有签名，会因 "No match found for function signature" 校验失败。
   实现在操作符链末尾追加 catch-all 表：不在内置/库函数名单中的函数名按不透明
   标量函数解析（参数任意，返回类型取第一个参数类型，零参按 `varchar`），
   血缘穿透参数继续分析。已知函数不受影响（保持原生语义，如 `count(phone)`）；
   字符串字面量必须用单引号，双引号在 PostgreSQL 词法下是标识符（列名）。
8. **写入语句（INSERT ... SELECT / CTAS）**：不把写入语句交给 Calcite 校验器
   （目标表通常不存在、无法通过目录校验），而是提取其源查询单独走既有管线，
   命中策略后用包装后的源查询文本重组语句（`INSERT INTO 目标 (列) <包装查询>`、
   `CREATE TABLE [IF NOT EXISTS] 目标 AS <包装查询>`）。`INSERT ... VALUES`
   纯字面量直通；VALUES 中藏子查询无法追踪来源，按安全策略直接失败。
   `REPLACE/VOLATILE/SET/MULTISET` 等 babel 方言变体直接失败。
