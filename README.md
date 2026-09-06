# sql-mask

基于 Apache Calcite 的 PostgreSQL SQL 脱敏改写服务。读取 YAML 中声明的表结构、
列脱敏策略和 UDF 参数，把查询改写为「原始查询作为内层、最外层对结果列调用脱敏
UDF」的 SQL。工具只做解析、校验、血缘分析和 SQL 输出，不连接查询引擎、不执行 SQL。

同一个 jar 提供两种使用方式：

- **Web 服务（默认）**：`java -jar target/sql-mask.jar` 启动 Spring Boot 服务，
  浏览器打开 `http://localhost:8080` 使用内置页面；
- **CLI**：`java -jar target/sql-mask.jar --metadata ... --sql/--input ...`，
  带命令行参数时自动走命令行模式，结果输出到 stdout 或文件。

## 构建

```bash
mvn test
mvn package
```

`mvn package` 产出可执行 fat jar：`target/sql-mask.jar`（已内置全部依赖）。

## Web 服务与页面

```bash
java -jar target/sql-mask.jar
# 默认端口 8080，可用 --server.port=9090 覆盖
```

打开 `http://localhost:8080`，页面提供结构化配置编辑器与改写结果展示：

- **表结构**：可增删改表（catalog / schema / 表名）及其列（列名 + 类型，含常用类型提示）；
- **列策略**：为「表结构」中声明的列绑定「策略定义」中的策略（下拉选择，重命名自动联动）；
- **策略定义**：策略名、UDF 名、有序标量参数（逗号分隔）均可编辑；
- **YAML 源码**：从表单实时生成；也可直接编辑后点「校验并应用到表单」，
  由后端复用 YAML 校验器解析回填，错误以路径化诊断展示；
- **执行改写**：按当前配置改写 SQL，逐语句展示结果（已脱敏/原样输出标记、
  可展开查看原始语句），支持一键复制；失败时展示错误码与原因。

## REST API

### POST /api/rewrite

```json
{
  "metadataYaml": "metadata:\n  tables:\n    - ...",
  "sql": "SELECT phone FROM customer;",
  "dialect": "postgresql"
}
```

成功返回 200：

```json
{
  "statements": [
    { "ordinal": 1, "originalSql": "...", "rewrittenSql": "...", "masked": true, "unchanged": false }
  ],
  "rewrittenSql": "……"
}
```

任意语句失败返回 400（结构化错误，整体失败、无部分结果）：

```json
{ "code": "VALIDATION_ERROR", "message": "statement 1: validation failed: ..." }
```

错误码：`CONFIG_ERROR`（YAML/参数）、`PARSE_ERROR`、`VALIDATION_ERROR`、
`UNSUPPORTED_STATEMENT`（DML/DDL/递归 CTE）、`LINEAGE_UNKNOWN`（来源无法追踪）、
`REWRITE_ERROR`（如重复输出列名需包装）、`IO_ERROR`、`INTERNAL_ERROR`。

### POST /api/config/parse

请求 `{ "metadataYaml": "..." }`；服务端解析并校验 YAML，返回结构化配置
（tables / columnPolicies / policies），供编辑器导入使用。

## 改写语义

- 接受 `SELECT`、`WITH ... SELECT`、`INSERT INTO ... SELECT`、
  `CREATE TABLE [IF NOT EXISTS] ... AS SELECT`；`UPDATE`/`DELETE`/其他 DDL 直接失败；
- **写入语句（INSERT ... SELECT / CTAS）对“写入的数据”脱敏**：把源查询包上外层
  脱敏包装，目标表名、目标列清单和 `IF NOT EXISTS` 等修饰原样保留——
  目标表通常是新表，无需在 YAML 中声明；
  `INSERT INTO ... VALUES`（纯字面量）无法追踪来源，原样直通；VALUES 中藏子查询
  的写法按安全策略直接失败；
- **引擎自定义函数可以直接使用**：Calcite 不认识的函数（如 `mask_idcard(c.phone, 'abc')`）
  按不透明标量函数解析——任意参数个数与类型均可，返回类型近似取第一个参数的类型
  （零参函数按 `varchar`）；血缘会穿透其参数，输出列照样按来源列策略脱敏；
  注意字符串字面量用**单引号**（`'ff'`），双引号在 PostgreSQL 语义中是标识符（列名）；
- 普通非递归 CTE、子查询、派生表参与最终输出列血缘追踪，CTE 内部不插入 UDF；
- 原始查询内部（WHERE/JOIN/GROUP BY/ORDER BY/LIMIT/DISTINCT 等）不做任何改动，
  脱敏 UDF 只出现在最外层，且每个命中策略的输出列只调用一次；
- 没有命中策略的输出列（含常量）原样引用；所有输出列都没有策略时不加包装，
  原样返回；
- 一个输出表达式有多个来源列时，按规范化 `catalog.schema.table.column`
  字典序稳定选择一个策略（不表达业务优先级，后续可替换为优先级选择器）；
- 输出列来源无法安全追踪（如输出位置的关联标量子查询）时按安全策略直接失败，
  不会静默放过；
- `WITH RECURSIVE`（自引用/环）直接失败，不无限展开；
- 重复输出别名是合法输入：无包装时原样输出；需要包装时，
  PostgreSQL 无法通过派生表列名可靠区分同名列，本版本直接失败。

## 行过滤

表声明支持可选的 `rowFilter` 字符串字段：该表的所有读取都会被静态附加一个行级
条件——改写时每个引用该表的位置替换为
`(SELECT * FROM <表> WHERE <rowFilter>) AS <别名>`，被过滤的行不进入聚合、去重、
排序、分页与写入。字段为空或空白视为未配置。

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active'"
      columns:
        - name: id
          type: bigint
        - name: status
          type: varchar

policies: {}
```

`policies` 是必填的顶层键；只使用行过滤、不定义任何脱敏策略时，写成 `policies: {}`。

条件白名单（registry 构建期逐节点检查，违反即 `CONFIG_ERROR`，消息带表名前缀）：

- 允许：被过滤表自身声明列的单段名引用、字面量（字符串/数值/布尔/日期时间）、
  布尔逻辑 `AND`/`OR`/`NOT`、比较 `=` `<>` `<` `<=` `>` `>=`、
  `IS NULL`/`IS NOT NULL`、`IS [NOT] DISTINCT FROM`、常量值列表 `IN`、
  算术 `+` `-` `*` `/` `%`；
- 禁止：一切函数调用（含未知 UDF、`CAST`、聚合、窗口）、子查询（标量/`IN`/`EXISTS`）、
  会话/用户/时间/随机函数（`CURRENT_USER`、`CURRENT_TIMESTAMP`、`random()` 等）、
  动态参数、序列访问及其他任何运算；
- 三值逻辑按 SQL 语义生效：`region = 'north'` 会排除 `region IS NULL` 的行，
  需要「NULL 也保留」时用 `IS NOT DISTINCT FROM`。

注入形态与覆盖范围：

- 与列脱敏叠加时，行过滤在内层生效（先于聚合、去重、排序、分页和写入），脱敏
  UDF 仍在最外层；过滤谓词始终使用底层明文列；
- 覆盖所有基表引用位置：主查询 FROM、JOIN 两侧、逗号连接、用户派生表与子查询
  内部、CTE 主体、嵌套 `UNION`/`INTERSECT`/`EXCEPT` 各分支、表达式位置子查询
  内部，以及 `INSERT ... SELECT` / `CTAS` 的源查询（目标表永不过滤）；
- 同一表被引用多次（含自连接）时，每个引用位置各自注入一份相同条件；
- CTE 名优先于同名基表；配置中没有任何 `rowFilter` 时改写器恒等返回，
  输出与不配置时逐字节一致。

错误行为清单：

- 条件解析失败、违反白名单、语义校验失败（未知列/类型/非布尔）：`CONFIG_ERROR`；
- 一段名命中多个声明表：`VALIDATION_ERROR`（不依赖 YAML 声明顺序，请改用三段全名）；
- 受控表以三段全名作列限定前缀（`crm.public.customer.id`）：`UNSUPPORTED_STATEMENT`
  （注入后该写法不再绑定，请改用别名或单段限定）；
- 未知 FROM 形态且子树引用受控表（`TABLESAMPLE`、`LATERAL`、`UNNEST` 等）：
  `UNSUPPORTED_STATEMENT`（fail-closed，不静默放过）；
- 根级集合操作：维持既有 `UNSUPPORTED_STATEMENT` 拒绝；嵌套在 CTE 体/子查询内的
  集合操作不受影响；
- 两段名 `schema.table`：不匹配、不注入，维持现状由校验器报错。

API 与页面：`POST /api/rewrite` 的每条语句新增 `"rowFiltered": true|false`
（true 表示该语句注入了行过滤条件，可与 `masked` 同时为 true）。页面结果卡片按
`(masked, rowFiltered)` 组合展示标签：「原样输出」「已行过滤」「已脱敏（外层包装
UDF）」「已脱敏（外层包装 UDF）+ 已行过滤」；表结构页签中每张表有独立的行过滤
输入框；YAML 导出对未配置的表不输出 `rowFilter:` 字段，YAML ⇄ 表单往返不丢失
配置、不残留空白字段。

## 输出格式

输出是 Calcite 生成的 SQL（PostgreSQL 方言），不保留原始排版与注释：
标识符按 PostgreSQL 规则加引号（保留大小写、转义保留字），`LIMIT n`
渲染为 PostgreSQL 同样支持的 `FETCH NEXT n ROWS ONLY`，函数名输出为
Calcite 的规范形式（如 `COUNT(*)`）。CLI 输出中语句之间以空行分隔，
每条语句以 `;` 结尾。

CTE 在血缘分析时会被内联为派生表（Calcite 的 CTE 引用不携带原始关系表达式），
这不影响改写结果：输出中的 CTE 保持原样在内层。

## CLI 用法

```bash
java -jar target/sql-mask.jar --metadata metadata.yaml --sql "SELECT phone FROM customer;"
java -jar target/sql-mask.jar --metadata metadata.yaml --input query.sql --output masked.sql
```

- `--metadata`（必填）：YAML 表结构与策略配置路径；
- `--sql` / `--input`：二选一（同时给出直接失败），输入内容可包含多条 SQL；
- `--output`：可选，未指定时改写结果输出到 stdout，UTF-8 写入；
- `--dialect`：可选，默认 `postgresql`（第一版唯一支持的方言）。

多条语句按顺序逐条处理；任意一条失败则整个命令失败，不输出部分改写结果
（`--output` 指定的文件也不会被创建或覆盖）。错误诊断输出到 stderr，
退出码：`0` 成功，`1` 处理失败，`2` 用法错误。

## metadata.yaml 示例

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

- `catalog`、`schema`、表名、列名、类型全部必填；未加引号的标识符按 PostgreSQL
  惯例统一折叠为小写作为策略键，YAML 中语义不同但折叠后相同的名字会被拒绝；
- SQL 引用的所有表必须在 `metadata.tables` 中声明；未加限定名的表按声明的
  `catalog.schema` 组合解析，命中多个时报歧义错误；
- 列策略严格使用完整的 `catalog.schema.table.column` 精确匹配；
  不支持 `search_path`、通配符、正则或标签；
- `arguments` 是有序标量（字符串/数字/布尔），原样渲染进最外层 UDF 调用；
- 支持的标量类型：`boolean`、`smallint`、`integer`、`bigint`、`real`、
  `double precision`、`decimal(p,s)`/`numeric(p,s)`、`char(n)`、`varchar(n)`、
  `text`、`date`、`timestamp[(p)]`、`timestamp with time zone`、`time[(p)]`、
  `time with time zone`；数组、JSON、复合类型和用户自定义类型暂不支持。
  （页面表单导入后，`text` 会规范化显示为 `varchar`，语义一致。）
