# 多引擎方言扩展设计方案（Trino / MySQL 先行）

日期：2026-09-06
状态：已评审（用户确认范围、方案与三段设计）
前置文档：`2026-09-05-sql-mask-cli-design.md`（其待办 #16「多查询引擎方言 SPI」由本文档落地）

## 1. 背景与目标

现有工具基于 Apache Calcite 实现 PostgreSQL SQL 脱敏改写（原始查询作内层、最外层
对结果列调用脱敏 UDF）。工具不连接查询引擎、不执行 SQL，"支持一个引擎"意味着：
解析该引擎方言的输入 SQL、按 YAML 元数据校验与血缘分析、改写、并渲染出**该引擎
接受的 SQL**。

本次目标：把方言能力从 PostgreSQL 扩展到 Trino 与 MySQL，同时完成方言 SPI
重构，使后续 Spark / StarRocks / Flink 成为增量工作。

不做跨引擎转写：输入与输出始终是同一方言。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 范围 | 先做 2 个引擎验证抽象层，确认后再铺开其余引擎 |
| 引擎选型 | Trino + MySQL（分属双引号/反引号、折叠小写/大小写不敏感、两套类型命名三个维度，最能验证 SPI 通用性；Calcite 内置两者 SqlDialect，Trino 另有独立 parser 可做输出验证） |
| YAML 类型名 | 不同引擎本来就是不同的表：YAML 跟随引擎，用该引擎的类型名，由方言层归一化到内部类型 |
| 语句范围 | 与 PostgreSQL 现状对齐：SELECT、WITH…SELECT、INSERT…SELECT、CTAS；引擎特有复杂变体安全失败 |
| 总体方案 | 方案 C：Calcite 单管线 + 方言 Profile（方案 A），Trino 输出用真 trino-parser 在测试中验证 |

## 2. 方案选型记录

- **方案 A（Calcite 单管线 + 方言 Profile）**：保持一条解析→校验→血缘→改写→渲染
  管线，每个引擎是一个声明式 profile。血缘分析只写一次，后续引擎是增量。
  代价：输入必须落在 Calcite 可解析的该引擎语法子集内，超出部分安全失败。
- **方案 B（每引擎原生解析器）**：语法保真最高，但每家 AST 不同导致血缘/校验/改写
  整套逻辑按引擎重写 ×N；Spark/Flink 依赖巨重；Flink 的 `flink-sql-parser` 是
  Calcite fork，包名 `org.apache.calcite.*` 与 calcite-core 同 classpath 冲突。
  不采用。
- **方案 C（采用）**：A 为主干 + 测试防线——test 作用域引入 `io.trino:trino-parser`，
  生成的 Trino SQL 用真引擎 parser 再解析断言合法。MySQL 无官方独立 parser，
  用 MySQL 配置的 Calcite round-trip + golden 测试兜底。

## 3. 方言 SPI 重构

### 3.1 DialectProfile

`DialectAdapter` 接口保持不变（`name/parse/validate/querySourceOf/
isPassThroughWrite/composeWriteStatement/unparse/capabilities`）。新增
`DialectProfile` record，把方言差异集中为声明式配置：

```text
name                     postgresql / trino / mysql
parserConfig             parser factory、quoting、unquotedCasing、quotedCasing、
                         caseSensitive、parser conformance
validatorConformance     校验期 SqlConformance
functionTable            SqlLibrary 链 + 方言补充函数；函数名一律大小写不敏感解析
nameMatcherCaseSensitive PG/Trino = true（解析期折叠 + 精确匹配）
                         MySQL = false（不折叠 + 大小写不敏感匹配）
typeResolver             该引擎 YAML 类型名 -> Calcite SqlTypeName（含精度/标度）
sqlDialect               渲染用 SqlDialect（Calcite 内置 TrinoSqlDialect/MysqlSqlDialect）
identifierPolicy         包装层标识符渲染策略（见 3.4）
schemaPathStyle          非限定名搜索路径的生成方式（见 3.5）
capabilities             DialectCapabilities
```

### 3.2 抽象基类与注册表

- 新增 `AbstractCalciteDialectAdapter`：承载通用流程——parse + 语句分类骨架、
  validate 的「原文快照 → CTE 内联 → 校验 → 转 RelNode」序列、unparse 配置、
  write 语句的 querySourceOf/isPassThroughWrite 通用逻辑。
  `classify` 的方言差异点与 `composeWriteStatement` 留作受保护钩子。
- `PostgresqlDialectAdapter` 重构为基于 profile 的实现，**行为零变化**
  （现有全部测试与 TPC-DS golden 保证回归）。
- 新增 `TrinoDialectAdapter`、`MysqlDialectAdapter`。
- `RewriteEngine.createDialect()` 替换为 `DialectRegistry`：名字
  `postgresql` / `trino` / `mysql`，未知方言抛 `CONFIG_ERROR` 并列出支持列表。

### 3.3 去 PG 化的共享代码

| 位置 | 现状 | 改造 |
|---|---|---|
| `SqlValidatorFactory` | 硬编码 `PostgresqlFunctions`、`SqlConformanceEnum.DEFAULT`、case-sensitive 匹配 | 函数表、conformance、名字匹配大小写、类型工厂由 profile 传入；`UnknownFunctionTable` 兜底保持（引擎自定义 UDF 跨方言直接可用，血缘穿透参数） |
| `SqlRewriteService` | 包装层字符串组装 + 字面量渲染硬编码 `PostgresqlSqlDialect` | 保持「原文快照作内层 + 字符串组装投影」结构不变（保证内层 byte 级是用户原始 SQL、PG 输出不变），但投影项的标识符、UDF 名、字面量渲染改走方言提供的渲染器 |
| `SqlIdentifierRenderer` | 硬编码 PG 保留字表与 PG 引号风格 | 按方言提供策略（见 3.4）；PG 策略保持现行为 |
| `TableMetadata.parseColumn` | 只解析 PostgreSQL 风格类型名 | 拆出方言 `TypeResolver`；`Column` 保留原始类型声明文本用于编辑器/API 回显 |
| `YamlConfigLoader` | 类型校验与方言无关 | 增加 dialect 参数，类型按方言解析校验 |
| `PostgresqlFunctions` | PG 函数表（含 varargs concat） | 保留为 PG profile 的函数表；大小写不敏感 lookup 的包装逻辑提取为共享工具，各方言复用 |

### 3.4 标识符渲染策略

- **postgresql / trino**：quote-when-needed——不匹配简单标识符模式、或命中该
  方言保留字集合时按方言风格加引号（PG 双引号，Trino 双引号）。PG 保留字集合
  沿用现有清单；Trino 维护一个小型保留字集合。
- **mysql**：包装层所有标识符一律反引号（`r`、输出列名、UDF 名）。MySQL 反引号
  包裹的名字（包括函数名）均合法；不维护 MySQL 保留字表，最安全。

包装层别名 `r` 在三个方言下都合法（MySQL 派生表必须有别名，已有）。

### 3.5 搜索路径

- postgresql / trino：维持现状——每个声明的 `catalog.schema` 对一条路径，
  非限定名唯一时解析，多命中报歧义。
- mysql：额外为每个 `schema` 生成一元素路径 `[schema]`，使 MySQL 惯用的两段名
  `db.table` 可解析；三段名与非限定名行为不变。多路径的解析顺序行为必须用
  pinning 测试钉死（见 §10.1；若二义性行为不符预期，备选方案是 MySQL 仅用
  `[schema]` 单路径，`catalog` 退化为纯 YAML 分组字段）。

### 3.6 能力声明

`DialectCapabilities.canWrapDuplicateOutputNames` 三方言均为 `false`（MySQL/Trino
均不支持派生表列别名列表）。重复输出列名需要包装时按现行策略失败。

## 4. 引擎 Profile 明细

### 4.1 Trino

| 项 | 值 |
|---|---|
| parser | 标准 `SqlParserImpl`（无 babel）；`Quoting.DOUBLE_QUOTE`；unquoted `TO_LOWER`；quoted `UNCHANGED`；caseSensitive true；conformance `DEFAULT` |
| 校验 | conformance `DEFAULT`；名字匹配 case-sensitive；函数表 = 标准库（Calcite 1.42 无 Trino 函数库，Trino 特有函数走未知函数兜底） |
| 渲染 | `TrinoSqlDialect` |
| 标识符 | quote-when-needed（双引号） |
| 搜索路径 | `[catalog, schema]` |

类型集（→ Calcite 类型）：`boolean`→BOOLEAN；`tinyint`/`smallint`/`integer`|`int`/`bigint`→同名整型；`real`→REAL；`double`→DOUBLE；`decimal(p,s)`|`decimal`→DECIMAL；`varchar`|`varchar(n)`→VARCHAR（无界/带精度）；`char(n)`|`char`→CHAR；`varbinary`→VARBINARY；`date`→DATE；`time[(p)] [with time zone]`→TIME / TIME_WITH_LOCAL_TIME_ZONE；`timestamp[(p)] [with time zone]`→TIMESTAMP / TIMESTAMP_WITH_LOCAL_TIME_ZONE。

`json`、`hyperloglog`、`qdigest`、`ipaddress` 等特殊类型第一版配置即报错。

写入语句：`INSERT INTO target [(cols)] SELECT`、`CREATE TABLE [IF NOT EXISTS]
target [(cols)] AS SELECT`；重组语法与 PG 相同形式。

已知安全失败项：
- 带 `WITH (…)` 表属性的 CTAS（Calcite 解析器不认识 → `PARSE_ERROR`）；
- 既有边界（递归 CTE、DML/DDL、输出位置标量子查询等）与 PG 相同。

### 4.2 MySQL

| 项 | 值 |
|---|---|
| parser | 标准 `SqlParserImpl`（无 babel）；`Quoting.BACK_TICK`；unquoted `UNCHANGED`；quoted `UNCHANGED`；caseSensitive false；conformance `MYSQL_5` |
| 校验 | conformance `MYSQL_5`；名字匹配 case-insensitive（对应 MySQL 列名大小写不敏感语义）；函数表 = `SqlLibrary.MYSQL` |
| 渲染 | `MysqlSqlDialect`（字符串转义、反引号由其处理） |
| 标识符 | 一律反引号 |
| 搜索路径 | `[catalog, schema]` + `[schema]` |

类型集（→ Calcite 类型）：`boolean`→BOOLEAN；`tinyint/smallint/mediumint/
int/integer/bigint[(n)]`→TINYINT/SMALLINT/INTEGER/INTEGER/BIGINT（显示宽度忽略）；
`decimal|dec|numeric(p,s)`→DECIMAL；`float`→REAL；`double [precision]`→DOUBLE；
`char[(n)]`→CHAR；`varchar(n)`→VARCHAR；`tinytext|mediumtext|text|longtext`→
VARCHAR（无界）；`binary[(n)]`|`varbinary(n)`→BINARY/VARBINARY；`date`→DATE；
`datetime[(p)]`→TIMESTAMP；`timestamp[(p)]`→TIMESTAMP_WITH_LOCAL_TIME_ZONE；
`time[(p)]`→TIME。

`datetime` 与 `timestamp` 的映射是校验期类型推导的近似（MySQL 两者时区语义
不同；类型只参与血缘/校验，不参与执行，真实语义以引擎为准）。
`json`、`year`、`enum`、`set`、`bit`、`geometry` 第一版配置即报错。

写入语句：`INSERT INTO target [(cols)] SELECT|VALUES`、
`CREATE TABLE [IF NOT EXISTS] target [(cols)] AS SELECT`（重组时保留/补 `AS`）；
`INSERT … VALUES` 纯字面量直通、VALUES 藏子查询安全失败，语义与 PG 一致。

已知安全失败项（文档列明）：
- `CREATE TABLE … SELECT` 不带 `AS`（请写 AS 形式）；
- `LIMIT offset, count` 逗号形式（请写 `LIMIT n OFFSET m`）；
- `INSERT … ON DUPLICATE KEY UPDATE`、`REPLACE INTO`；
- 带表属性的 CTAS 变体。

### 4.3 跨方言保持不变的语义

- 多来源策略命中时按规范化 `catalog.schema.table.column` 字典序稳定选择；
- 策略键仍按小写折叠归一化（MySQL/Trino 与大小写不敏感/折叠语义一致；
  引号标识符需 YAML 声明一致大小写，行为同 PG）；
- 血缘 `UNKNOWN` 安全失败；无策略输出列原样直通；
- 未知函数兜底（不透明标量函数、返回类型近似、血缘穿透参数）；
- 重复输出列名需要包装时失败；
- 多条语句任一失败则整体失败、无部分结果。

## 5. 错误处理

错误码体系不变。增强两点：

1. 诊断信息带方言名：`statement 1: parse error (trino): …`；
2. 类型名不属于所选引擎时报带方言上下文的消息：
   `unknown type 'datetime' for dialect trino`。

未知方言：`CONFIG_ERROR`，消息列出全部支持名字。整体失败、无部分结果原则不变。

## 6. 测试策略

1. **PG 回归零变化**：现有全部单测 + TPC-DS 用例保持通过，作为重构正确性的
   首要证据；
2. **每方言单测**：`TrinoDialectAdapterTest` / `MysqlDialectAdapterTest` 镜像
   PG 版场景清单（包装形状、引号、大小写、字符串转义、写入语句重组、类型解析、
   重复列名拒绝、未知函数兜底）；
3. **集成测试参数化**：`SqlMaskIntegrationTest` 按方言参数化，同一批场景跨三方言；
4. **Trino 输出真 parser 验证**：test 作用域引入 `io.trino:trino-parser`，
   每条 Trino 改写输出用真 Trino parser 再解析断言合法；
5. **MySQL 输出验证**：MySQL parser 配置的 Calcite round-trip 再解析 + golden
   测试；不引入 jsqlparser 等第三方；
6. **TPC-DS 可移植子集**：`tpcds/` 查询集加方言参数跑 trino/mysql，能改写的
   语句生成 golden 审查入库，方言特有失败进失败清单；
7. **配置校验测试**：每方言类型别名解析与非法类型报错。

## 7. API / 前端

- CLI：`--dialect {postgresql|trino|mysql}`，默认 `postgresql`；
- REST `/api/rewrite`：`dialect` 字段接受三个名字，缺省 `postgresql`（向后兼容）；
- `/api/config/parse`：新增可选 `dialect` 字段（默认 `postgresql`），类型校验按
  方言执行；
- 前端 `index.html`：方言选择器（改写请求与配置校验共用，当前第 201 行徽标、
  第 668 行硬编码 `dialect: "postgresql"`），PG 特有提示文案按方言切换，
  类型提示下拉按方言给常用类型；不重构页面结构；
- README：方言说明、各引擎类型集、安全失败清单。

## 8. 明确不做（YAGNI）

- 跨引擎转写；
- 连接引擎执行 / EXPLAIN 验证；
- 方言插件热加载（加引擎 = 加一个 profile 类 + 注册一行）；
- `DialectCapabilities` 新能力（结构留待方言需要时扩展）；
- Spark / StarRocks / Flink 的实现（见 §9 路线）。

## 9. 后续引擎路线（本次不实现）

- **Spark**：反引号、大小写不敏感、不折叠；Calcite 1.42 内置 `SparkSqlDialect`
  与 `SqlLibrary.SPARK`/`HIVE`；`USING`/`INSERT OVERWRITE` 等写入变体为增量钩子。
- **StarRocks**：MySQL-like；Calcite 1.42 已内置 `StarRocksSqlDialect`；函数库
  可复用 `SqlLibrary.MYSQL` 子集或自定义补充。
- **Flink**：Calcite 无 Flink 方言（Flink 官方方言在其 Calcite fork 内），
  需自定义 `SqlDialect`（反引号、LIMIT）；`flink-sql-parser` 与 calcite-core
  包名冲突，输出验证只能用 golden 或独立模块。

三者均为：新增一个 profile + 注册表登记 + 该引擎测试集，SPI 不需要再改。

## 10. 自评审发现与待办项（2026-09-06）

### 10.1 设计待钉死项

1. **MySQL 多搜索路径的解析顺序**：`[catalog, schema]` 与 `[schema]` 双路径下，
   CalciteCatalogReader 对 `db.table`（仅 `[schema]` 路径可解析）与非限定名
   （两类路径都可解析）是首匹配还是报二义，行为未验证。实现时先写 pinning
   测试钉死实际行为并写入文档；若双路径产生错误二义，MySQL profile 改用
   `[schema]` 单路径（`catalog` 仅作 YAML 逻辑分组），两段名/非限定名仍可解析。
2. **Trino 保留字集合需要具体化**：§3.4 的「小型集合」实现时必须从 Trino 官方
   文档的 reserved words 派生完整清单，并用「保留字命名列 + 包装」的测试覆盖，
   不得凭印象截取。

### 10.2 实现期验证项（每项都要有对应测试）

3. **方言 SqlDialect 行为**：`TrinoSqlDialect` / `MysqlSqlDialect` 的实例化方式
   （context/DEFAULT）与字面量转义行为用测试钉死，尤其 MySQL 字符串参数的反斜杠
   转义（默认 MySQL 把 `\` 视为转义符）。
4. **MySQL CTAS 列定义边界**：`CREATE TABLE t (a INT PRIMARY KEY, …) AS SELECT`
   这类带约束的列定义能否被 Calcite DDL parser 解析未验证；解析不了的进安全
   失败清单。另外重组语句时列定义被重渲染为 Calcite 规范形式（`INT`→`INTEGER`
   等），golden 测试确认重渲染结果仍是合法 MySQL。
5. **`SqlConformanceEnum.MYSQL_5` 回归影响**：对既有管线（未知函数兜底、类型
   推导、聚合不剪枝、`count(col)` 血缘）跑全量测试确认无副作用。
6. **trino-parser 依赖**：选定版本并验证与 calcite 的传递依赖（guava 等）无
   冲突（test scope）；该依赖只出现在测试代码，主代码不得引用。
7. **CteExpander 方言假设**：其注释声明按「PostgreSQL 作用域」做 CTE 遮蔽，
   需确认逻辑对 MySQL/Trino 的 CTE 语义等价；有 PG 假设则泛化。
8. **schemaPaths 生成去重**：路径生成目前同时存在于
   `YamlCalciteSchemaFactory.schemaPaths` 与 `PostgresqlDialectAdapter.schemaPaths`
   （重复代码），profile 化时统一为一处，由 `schemaPathStyle` 驱动。

### 10.3 测试计划澄清

9. **集成测试的方言变体**：§6.3 的「同一批场景」实现为按方言维护 SQL 变体集——
   PG 特有语法（`::` 强转、`$$` 字符串等）在 trino/mysql 场景用等价写法，
   不强行让一条 SQL 跑三方言。
10. **TPC-DS 跨方言元数据**：`tpcds/metadata.yaml` 是 PG 类型名；trino/mysql
    跑 TPC-DS 子集前需生成按方言类型的 metadata 文件（类型名机械翻译即可），
    作为测试资源入库。

### 10.4 文档补充项

11. **内层 SQL 的引擎语义说明**：用户原始 SQL 内层保留原文，其中的反斜杠转义、
    双引号字符串等由目标引擎按自身语义解释；Calcite 校验期的解释可能不同，但
    只影响校验不影响脱敏正确性（内层字节不变）。此限制写入 README。
