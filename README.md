# sql-mask

基于 Apache Calcite 的 PostgreSQL SQL 脱敏改写服务。读取 YAML 中声明的表结构、
列脱敏策略和 UDF 参数，把查询改写为「原始查询作为内层、最外层对结果列调用脱敏
UDF」的 SQL。工具只做解析、校验、血缘分析和 SQL 输出，从不执行业务 SQL；唯一的
数据库访问是 `--pull-metadata` 的只读元数据采集。

同一个 jar 提供两种使用方式：

- **Web 服务（默认）**：`java -jar mask-core/target/sql-mask.jar` 启动 Spring Boot 服务，
  浏览器打开 `http://localhost:8080` 使用内置页面；
- **CLI**：`java -jar mask-core/target/sql-mask.jar --metadata ... --sql/--input ...`，
  带命令行参数时自动走命令行模式，结果输出到 stdout 或文件。

策略有两个可选来源（二选一，同时非空会显式报错）：旧格式 `metadata.yaml` 内嵌的
`policies` / `columns` / `rowFilter`（对所有人无条件生效，行为与历史版本逐字节一致），
或独立的 Ranger 式 `policies.yaml`（见「策略文件」章节：主体 `users`/`groups` 维度、
资源通配与 `priority`），后者通过 `--policies` / 请求字段 `policyYaml` 提供并携带
查询主体（`--user`/`--groups` 或请求字段 `user`/`groups`）。

## 元数据采集（--pull-metadata）

`--pull-metadata` 连接一个 PostgreSQL 库，把库表结构拉取成 metadata 骨架 YAML：
表、列、类型都已生成，列策略（`columns`）与行过滤（`rowFilter`）需要人工后补。
采集只读 `pg_catalog`，JDBC 连接以只读模式打开，不执行任何业务 SQL。

```bash
java -jar target/sql-mask.jar --pull-metadata --host 127.0.0.1 \
  --database crm --user postgres --password 'PgTest2026' --output crm.yaml
```

- `--database` / `--user` / `--output` 必填；`--host` 缺省 127.0.0.1，
  `--port` 缺省 5432；
- `--schema`：schema 过滤，可重复给出多个；缺省导出全部非系统 schema；
- `--include-views`：连同视图与物化视图一起导出；
- `--strict`：只要有列类型降级为 varchar 就拒绝导出（退出码 1，
  错误码 `STRICT_DEGRADED`）；
- 密码来源：`--password` 优先于环境变量 `PGPASSWORD`，两者都没有则退出码 2。

行为要点：

- 类型映射清单见 spec 第 3 节（`docs/superpowers/specs/2026-09-06-metadata-introspection-design.md`），
  与「metadata.yaml 示例」一节的支持类型一致：`boolean`、`smallint`、`integer`、
  `bigint`、`real`、`double precision`、`numeric(p,s)`、`char(n)`、`varchar(n)`、
  `text`、`date`、`timestamp[(p)]`、`timestamptz`、`time[(p)]`、`timetz`；
- 不可映射的类型（`jsonb`、`uuid`、数组等）不报错、列不消失：降级为 `varchar`
  并在 stderr 输出一条警告（`... PG type jsonb is not representable, degraded to varchar`）；
- 库里没有可导出的表时输出空骨架 `tables: []`，退出码仍为 0；
- 输出确定性：同一数据库重复导出逐字节一致（可直接 diff 提交审阅），成功时
  stdout 摘要 `introspected N tables / M columns / K warnings`；
- 失败路径（连接失败、`--strict` 命中降级）不会创建或覆盖 `--output` 文件。

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
- **从数据库导入**：在「表结构」页签填写连接信息（主机/端口/库/用户/密码，
  可选 schema 过滤与包含视图），调用 `POST /api/metadata/pull` 拉取表结构骨架
  合并进表单，类型降级警告逐条展示；
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
  "policyYaml": "policies:\n  - name: mask-phone\n    ...",
  "sql": "SELECT phone FROM customer;",
  "dialect": "postgresql",
  "user": "alice",
  "groups": ["devs", "ops"]
}
```

- `metadataYaml`、`sql` 必填；`policyYaml`、`user`、`groups` 可选；
- 给出 `policyYaml` 时 metadata 的 `policies` / `columns` / `rowFilter` 必须为空，
  否则 `CONFIG_ERROR`（策略来源必须唯一）；
- `user`/`groups` 是查询主体：仅 `policies.yaml` 中匹配该主体的策略项生效，
  缺省视为匿名主体（只有 `*` 通配项命中）。

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

### POST /api/metadata/pull

连接一个 PostgreSQL 库做只读元数据采集，返回骨架 YAML（与 CLI 的
`--pull-metadata` 同一实现，表结构导入页面也走这个接口）：

```json
{
  "host": "127.0.0.1",
  "port": 5432,
  "database": "crm",
  "user": "postgres",
  "password": "***",
  "schemas": ["public"],
  "includeViews": false
}
```

`database`、`user`、`password` 必填；`host`/`port` 缺省 127.0.0.1:5432，
`schemas` 缺省导出全部非系统 schema。成功返回 200：

```json
{
  "yaml": "metadata:\n  tables:\n    - ...",
  "tableCount": 4,
  "columnCount": 21,
  "warnings": [],
  "catalog": "crm"
}
```

失败返回 400：`CONFIG_ERROR`（参数缺失或空白）、`INTROSPECT_ERROR`（连接或
采集失败）。密码只在本次请求内使用，不写日志、不出现在响应中。

### POST /api/config/parse

请求 `{ "metadataYaml": "..." }`；服务端解析并校验 YAML，返回结构化配置
（tables / columnPolicies / policies），供编辑器导入使用。

### POST /api/policies/parse

请求 `{ "policyYaml": "..." }`；解析并校验 Ranger 式策略文件，成功返回 200：

```json
{
  "policies": [
    { "name": "mask-phone", "enabled": true, "priority": 0, "type": "data_mask",
      "resources": [{ "catalog": "crm", "schema": "public", "table": "customer", "column": "phone" }],
      "itemCount": 1 }
  ]
}
```

失败返回 400 `{ "code": "CONFIG_ERROR", "message": "..." }`，消息以 YAML 路径定位
（如 `policies.yaml: policies[0].dataMaskItems[1]`）。供页面「策略文件」页签校验回填，
也是将来独立策略服务与引擎共用的契约。

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

## 策略文件（policies.yaml）

Ranger 式策略文件把「谁（users/groups）对什么资源（catalog.schema.table.column）受
什么策略影响」声明为独立文件，与 metadata.yaml 分离：

```yaml
policies:
  - name: mask-customer-phone
    enabled: true
    priority: 0
    resources:
      - catalog: crm
        schema: public
        table: customer
        column: [phone, email]     # 标量、列表或 "*"
    dataMaskItems:
      - groups: ["*"]              # 或 users: [...]；至少一个非空
        udf: mask_phone
        arguments: [3, 4]

  - name: filter-archived-orders
    resources:
      - catalog: crm
        schema: public
        table: orders
    rowFilterItems:
      - groups: ["*"]
        filterExpr: "status <> 'archived'"
```

规则要点：

- 一个策略只能声明 `dataMaskItems` 或 `rowFilterItems` 之一；dataMask 资源必须到
  column 级（可为 `*`），rowFilter 资源到 table 级且不得带 column；
- 主体选择器 `users` / `groups` 至少一个非空，`*` 是唯一通配符（要表达"所有人"
  显式写 `groups: ["*"]`）；匹配特异性：user 精确 > group 精确 > `*`；请求未带
  主体时视为匿名（只有 `*` 项命中）；
- 多策略命中同一资源：`enabled: false` 跳过 → `priority` 高者优先 → 同优先级按
  声明顺序；掩码每列只取唯一命中，行过滤命中项按决策顺序 AND 叠加；
- 策略资源必须命中至少一张声明表/列，否则 `CONFIG_ERROR`（fail-closed，防手误
  静默失效）；`filterExpr` 复用行过滤白名单，错误消息带 `policy '<名>': filterExpr` 前缀；
- 与 metadata 内嵌策略互斥：两套来源同时非空即 `CONFIG_ERROR`；
- 页面「策略文件」页签发送的是「校验并应用」通过后的内容——应用后再编辑、
  未重新校验的内容不会随改写请求发送。

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
UDF）」「已脱敏（外层包装 UDF） + 已行过滤」；表结构页签中每张表有独立的行过滤
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
java -jar mask-core/target/sql-mask.jar --metadata metadata.yaml --sql "SELECT phone FROM customer;"
java -jar mask-core/target/sql-mask.jar --metadata metadata.yaml --input query.sql --output masked.sql
java -jar mask-core/target/sql-mask.jar --metadata metadata.yaml --policies policies.yaml \
  --user alice --groups devs,ops --sql "SELECT phone FROM customer;"
```

- `--metadata`（必填）：YAML 表结构配置路径；
- `--policies`：可选，Ranger 式 policies.yaml 路径；给出时 metadata 不得再声明
  `policies`/`columns`/`rowFilter`（互斥，违反报 `CONFIG_ERROR`，退出码 1）；
- `--user`：按模式复用——`--pull-metadata` 模式下为数据库用户，改写模式下为查询
  主体用户（Ranger 式策略匹配）；
- `--groups g1,g2`：查询主体组，逗号分隔、可重复出现（合并去重保序）；仅改写模式
  可用，与 `--pull-metadata` 同用报用法错误（退出码 2）；
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
