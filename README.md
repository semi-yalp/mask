# sql-mask

基于 Apache Calcite 的多引擎 SQL 脱敏改写服务，支持 **PostgreSQL / Trino / MySQL**
三种方言（输入与输出同为该方言，不做跨引擎转写）。读取 YAML 中声明的表结构、
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

`--pull-metadata` 连接一个 PostgreSQL、MySQL 或 Trino 数据库，把库表结构拉取成
metadata 骨架 YAML：表、列、类型都已生成，列策略（`columns`）与行过滤
（`rowFilter`）需要人工后补。采集只读系统目录（PostgreSQL `pg_catalog`，
MySQL / Trino `information_schema`），JDBC 连接以只读模式打开，不执行任何业务 SQL。

引擎用 `--engine` 指定：`postgresql`（默认）、`mysql`、`trino`（CLI 侧大小写
不敏感）：

```bash
# PostgreSQL（缺省引擎，行为与历史版本一致）
java -jar target/sql-mask.jar --pull-metadata --host 127.0.0.1 \
  --database crm --user postgres --password 'PgTest2026' --output crm.yaml

# MySQL：--database 是库名，导出的 catalog/schema 都等于该库
java -jar target/sql-mask.jar --pull-metadata --engine mysql --port 3306 \
  --database shop --user root --password 'MyTest2026' --output mysql-shop.yaml

# Trino：--database 是 catalog 名（即 jdbc:trino://host:port/<catalog>）
java -jar target/sql-mask.jar --pull-metadata --engine trino --port 8080 \
  --database crm --user trino --password x --output trino-crm.yaml
```

- `--database` / `--user` / `--output` 必填；`--host` 缺省 127.0.0.1，
  `--port` 缺省 5432；
- `--database` 语义按引擎：PostgreSQL / MySQL 是库名，Trino 是 catalog 名；
- Trino 无密码认证：`--password` 传任意非空占位值（如 `x`）即可，CLI 要求
  密码非空；明文（`sslmode=disable`，缺省）连接不发送密码——占位密码不会进
  JDBC Properties，被静默丢弃；Trino 真实启用密码认证时须加
  `--sslmode require`（经 TLS 发送）。Web 请求体暂无 sslmode 字段，密码认证的
  Trino 暂只能走 CLI；
- `--schema`：schema 过滤，可重复给出多个；缺省导出全部非系统 schema
  （PostgreSQL）、当前库（MySQL）或该 catalog 下全部 schema（Trino）；
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
- MySQL：`unsigned` 后缀被剥离并告警（取值范围语义丢失，如 `int unsigned` →
  `int`）；`json`、`enum`、`set` 等清单外类型同样降级为 `varchar` 并告警
  （`... mysql type json is not representable, degraded to varchar`）；
- Trino：`array` / `map` / `row` 等复杂类型与清单外类型（含 `json`）降级为
  `varchar` 并告警（`... trino type json is not representable, degraded to varchar`）；
- 无论哪个引擎，写进 YAML 的每个类型最终都通过对应方言的 TypeResolver
  （mysql / trino 方言各一套）校验——生成的类型声明保证能被该引擎的改写
  链路接受；
- 库里没有可导出的表时输出空骨架 `tables: []`，退出码仍为 0；
- 输出确定性：同一数据库重复导出逐字节一致（可直接 diff 提交审阅），成功时
  stdout 摘要 `introspected N tables / M columns / K warnings`；
- 失败路径（连接失败、`--strict` 命中降级）不会创建或覆盖 `--output` 文件。

已知行为（当前版本有意保留）：

- Web 与 CLI 的引擎名均经注册表（`MetadataIntrospectors.byEngine`）做
  trim+小写归一化，`MySQL` 这类写法两侧行为一致；显式传入非法引擎时
  Web 报 `CONFIG_ERROR`，CLI 退出码 2；
- 改写模式下 `--engine` 被忽略，目标方言由 `--dialect` 决定；
- 页面导入弹窗标题仍写「连接 PostgreSQL 拉取元数据」，但通过弹窗顶部的引擎
  下拉框三引擎均可用。

## 构建

```bash
mvn test
mvn package
```

`mvn package` 产出可执行 fat jar：`target/sql-mask.jar`（已内置全部依赖）。

依赖版本约定：`io.trino:trino-jdbc:446` 需与 test-scope 的
`io.trino:trino-parser:446` 保持同一版本对齐（升级 JDBC 驱动时同步升级测试用
parser，保证 golden 校验与驱动行为一致）。另注：CLI 的 `--connect-timeout` 对
Trino 无效（446 驱动不支持该 URL 属性，Trino 连接使用驱动默认超时）。

## Web 服务与页面

```bash
java -jar target/sql-mask.jar
# 默认端口 8080，可用 --server.port=9090 覆盖
```

打开 `http://localhost:8080`，页面提供结构化配置编辑器与改写结果展示：

- **表结构**：可增删改表（catalog / schema / 表名）及其列（列名 + 类型，含常用类型提示）；
- **从数据库导入**：在「表结构」页签填写连接信息（引擎下拉框
  PostgreSQL/MySQL/Trino、主机/端口/库/用户/密码，可选 schema 过滤与包含视图），
  调用 `POST /api/metadata/pull` 拉取表结构骨架合并进表单，类型降级警告逐条展示；
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

连接一个 PostgreSQL、MySQL 或 Trino 数据库做只读元数据采集，返回骨架 YAML
（与 CLI 的 `--pull-metadata` 同一实现，表结构导入页面也走这个接口）：

```json
{
  "engine": "postgresql",
  "host": "127.0.0.1",
  "port": 5432,
  "database": "crm",
  "user": "postgres",
  "password": "***",
  "schemas": ["public"],
  "includeViews": false
}
```

- `engine` 可选：`postgresql` / `mysql` / `trino`，缺省 `postgresql`；未知引擎
  返回 `CONFIG_ERROR`（建议按小写传入，见上文「已知行为」）；
- `database`、`user`、`password` 必填；`database` 语义按引擎：
  PostgreSQL / MySQL 是库名，Trino 是 catalog 名；
- `host`/`port` 缺省 127.0.0.1:5432，`schemas` 缺省导出全部非系统 schema
  （PostgreSQL）、当前库（MySQL）或该 catalog 下全部 schema（Trino）。

成功返回 200（`catalog` 对 MySQL / Trino 即请求的 `database`）：

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

请求 `{ "metadataYaml": "...", "dialect": "postgresql" }`；`dialect` 可选
（`postgresql`/`trino`/`mysql`，缺省 `postgresql`），类型按该方言解析校验。
服务端解析并校验 YAML，返回结构化配置（tables / columnPolicies / policies），
供编辑器导入使用。

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
- **包装目标类型**：最外层 UDF 的第一个参数是输出列的值，工具不自动插入类型
  转换。聚合列（如 `count(phone)`）命中文本型策略时包装目标是数值，真库执行
  需要 UDF 有对应类型重载（如 `mask_phone(bigint, integer, integer)`），或避免
  对聚合列绑定文本型策略；
- `ORDER BY` 可以引用 SELECT 投影之外的列（含表别名限定），转换层会把排序键
  投影回校验后的输出形态后再做血缘与包装，内层 `ORDER BY` 原样保留。

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
        column: [phone, email]     # 标量、列表或 "*"（也支持 glob，如 "phone_*"）
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
- 资源四级（catalog/schema/table/column）均支持 glob 通配：`*` 匹配本级内的任意
  字符序列（含空串），可出现在任意位置、可出现多次（如 `order_*`、`*_bak`、
  `log_*_v2`；裸 `*` 即"该级全部"）。模式与标识符一样先折叠为小写再匹配，标识符
  中的字面 `*` 无法表达；主体选择器不受影响（仍只支持精确值或 `*`）。管理面
  （policy server）资源仍要求精确名，写入含 `*` 的资源会被显式拒绝；
- 策略资源（含 glob 模式）必须命中至少一张声明表/列，否则 `CONFIG_ERROR`
  （fail-closed，防手误静默失效）；`filterExpr` 复用行过滤白名单，错误消息带
  `policy '<名>': filterExpr` 前缀；
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
- 两段名 `schema.table`：MySQL 会照常注入行过滤（其校验器经声明 catalog 解析
  两段名）；多 catalog 声明了同名 `schema.table` 造成歧义时显式
  `VALIDATION_ERROR`（请改用三段全名）。PostgreSQL / Trino 的校验器不支持
  两段名，维持原样由校验器报错；
- 名称匹配与校验器口径一致：MySQL 大小写不敏感（`FROM Customer` 与声明
  `customer` 视为同一张受控表，照常注入），PostgreSQL / Trino 引号外的引用
  已折叠小写、引号引用按大小写精确匹配。

API 与页面：`POST /api/rewrite` 的每条语句新增 `"rowFiltered": true|false`
（true 表示该语句注入了行过滤条件，可与 `masked` 同时为 true）。页面结果卡片按
`(masked, rowFiltered)` 组合展示标签：「原样输出」「已行过滤」「已脱敏（外层包装
UDF）」「已脱敏（外层包装 UDF） + 已行过滤」；表结构页签中每张表有独立的行过滤
输入框；YAML 导出对未配置的表不输出 `rowFilter:` 字段，YAML ⇄ 表单往返不丢失
配置、不残留空白字段。

## 输出格式

输出是 Calcite 生成的 SQL（按 `--dialect`/请求 `dialect` 选择方言），不保留原始
排版与注释。以 PostgreSQL 为例：标识符按需加引号（保留大小写、转义保留字），
`LIMIT n` 渲染为 PostgreSQL 同样支持的 `FETCH NEXT n ROWS ONLY`，函数名输出为
Calcite 的规范形式（如 `COUNT(*)`）；Trino/MySQL 的引号风格与关键字渲染见
「方言支持」。内层查询是校验前的原文渲染快照（POSTGRESQL 例外地原样支持
`ASYMMETRIC`）。CLI 输出中语句之间以空行分隔，每条语句以 `;` 结尾。

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
- `--dialect`：可选，`postgresql`（默认）/ `trino` / `mysql`，未知名报用法错误
  （退出码 2）并列出支持列表；输入 SQL 必须落在「Calcite 可解析的该引擎语法
  子集」内，引擎特有语法超出部分按 `PARSE_ERROR` 安全失败。

## 方言支持

三个方言共享同一条解析→校验→血缘→改写→渲染管线，差异集中在方言 profile
（引号风格、标识符大小写语义、类型命名、内置函数库、输出渲染）：

| 维度 | PostgreSQL | Trino | MySQL |
|---|---|---|---|
| 标识符引号 | 双引号（按需） | 双引号（按需） | 反引号（包装层一律加） |
| 非引号标识符 | 折叠小写 | 折叠小写 | 不折叠、大小写不敏感匹配 |
| 字符串字面量 | 单引号 | 单引号 | 单引号；**双引号不是标识符**（直接被拒，fail-closed） |
| 两段名 `db.table` | 不支持（用三段名或不加限定） | 不支持（校验器直接拒绝，用三段名） | 支持（db = 声明的 schema） |
| 非限定名同名冲突 | 按 schema 名字母序**静默首匹配**（非报错） | 同左 | 同左 |

包装层（最外层投影）的标识符渲染：PostgreSQL/Trino 按需加引号（保留字、大小写、
特殊字符），MySQL 一律反引号。`BETWEEN` / `NOT BETWEEN` 在 Trino 与 MySQL 输出
中保留原义（Calcite 默认渲染成引擎不支持的 `BETWEEN ASYMMETRIC`，两方言各自
覆盖了该渲染；PostgreSQL 原生支持 ASYMMETRIC，无需处理）；`BETWEEN SYMMETRIC`
不做特殊处理，按 Calcite 原渲染输出——PostgreSQL 可执行；Trino / MySQL 会在
引擎侧报错。

### 各引擎类型集（metadata.yaml 的 `type:`）

- **PostgreSQL**：`boolean`、`smallint`、`integer`、`bigint`、`real`、
  `double precision`、`decimal(p,s)`/`numeric(p,s)`、`char(n)`、`varchar(n)`、
  `text`、`date`、`timestamp[(p)]`、`timestamp with time zone`/`timestamptz`、
  `time[(p)]`、`time with time zone`/`timetz`；
- **Trino**：`boolean`、`tinyint`~`bigint`、`real`、`double`、`decimal(p,s)`、
  `varchar[(n)]`、`char(n)`、`varbinary`、`date`、`time[(p)] [with time zone]`、
  `timestamp[(p)] [with time zone]`（`json`、`hyperloglog` 等不支持）；
- **MySQL**：`boolean`、`tinyint/smallint/mediumint/int/integer/bigint[(n)]`、
  `decimal(p,s)`、`float`、`double`、`char[(n)]`、`varchar(n)`、
  `tinytext/mediumtext/text/longtext`（→ varchar）、`binary[(n)]`、`varbinary(n)`、
  `date`、`datetime[(p)]`、`timestamp[(p)]`、`time[(p)]`
  （`json`、`year`、`enum`、`set`、`bit`、`geometry` 不支持）。

类型声明在编辑器/API 回显时**原样保留**（不再规范化，例如 `text` 回显 `text`）。

### 各引擎安全失败清单

- **MySQL**：`CREATE TABLE … SELECT` 不带 `AS`（请写 AS 形式）；
  `INSERT … ON DUPLICATE KEY UPDATE`、`REPLACE INTO`；带表属性的 CTAS 变体；
  双引号字符串形式不存在（双引号标记被解析器直接拒绝）。
  注意 `LIMIT offset, count` 逗号形式**可以**解析（MYSQL_5 语义）。
- **Trino**：带 `WITH (…)` 表属性的 CTAS。
- **通用**：`UPDATE`/`DELETE`/其他 DML/DDL、递归 CTE、输出位置关联标量子查询、
  需要包装的重复输出列名。
- **MySQL 反斜杠边界（有意保留）**：UDF 字符串参数中的反斜杠**不会**被双写；
  在 MySQL 默认转义模式下，含反斜杠的参数值会按 MySQL 转义规则被引擎二次解释
  （策略参数尽量避免反斜杠）。
- **CTE 自引用**：CTE 主体内引用同名的 CTE（而非基础表）在所有方言下按递归 CTE
  拒绝（fail-closed；真实 PostgreSQL/MySQL 可能按基础表解析——请显式改名）。
- **内层 SQL 语义**：原始查询以内层原文快照保留，其中的转义、引号等由目标引擎
  按自身语义解释；Calcite 校验期的解释可能不同，但只影响校验、不影响脱敏正确性。

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

- `catalog`、`schema`、表名、列名、类型全部必填；策略键统一折叠为小写
  （与各方言的标识符折叠/大小写不敏感语义一致；引号标识符需与声明大小写一致），
  YAML 中语义不同但折叠后相同的名字会被拒绝；
- SQL 引用的所有表必须在 `metadata.tables` 中声明；未加限定名的表按声明的
  `catalog.schema` 组合解析，多个 schema 声明了同名表时按 schema 名字母序
  静默取第一个（非报错——请用全限定名避免歧义）；
- 列策略严格使用完整的 `catalog.schema.table.column` 精确匹配；
  不支持 `search_path`、通配符、正则或标签；
- `arguments` 是有序标量（字符串/数字/布尔），原样渲染进最外层 UDF 调用；
- 支持的标量类型（默认 `postgresql` 方言清单）：`boolean`、`smallint`、`integer`、
  `bigint`、`real`、`double precision`、`decimal(p,s)`/`numeric(p,s)`、`char(n)`、
  `varchar(n)`、`text`、`date`、`timestamp[(p)]`、`timestamp with time zone`、
  `time[(p)]`、`time with time zone`；其他方言的类型清单见「方言支持」一节；
  数组、JSON、复合类型和用户自定义类型暂不支持（各引擎不可用类型清单同样见
  「方言支持」）。类型声明在编辑器/API 回显时原样保留（如 `text` 回显 `text`）。

## 元数据微服务（mask-metadata，8082）

引擎实例 + 表结构的唯一事实源：实例 CRUD、三引擎采集（复用 core introspect）、
YAML 导入、数据面 `GET /api/metadata/instances/{name}`（tables 段等价 JSON）。
策略服务按版本轮询拉取；拉不到用上个 version（stale-but-available）。

本地起套：`mvn -pl mask-metadata -am package && docker compose -f docker-compose.metadata.yml up`
（需先 `mvn -pl mask-metadata -am package` 生成 fat jar）。

- 鉴权：`X-Api-Key`（服务端 Key 来自 `METADATA_API_KEY`；未配置 = 全 401）
- 密码：实例只登记环境变量名（`passwordRef`，推荐 `SQLMASK_DS_<INSTANCE>_PASSWORD`），
  采集时服务端解析；密码不落库、不进日志、不进 URL
- 导入：`POST /api/instances/import` 只吃 `metadata.tables`；表声明含 `rowFilter`
  字段会被 400 拒绝——行过滤请在策略服务配置为 row_filter 策略

## 查询服务（mask-query，8083）

统一查询 API 数据面：调用方提交「原始 SQL + 目标实例 + 查询主体」，服务内部
依次完成「拉实例连接信息（mask-metadata）→ 按实例改写（mask-core 服务模式，
改写不可绕过）→ JDBC 执行改写产物 → 只返回脱敏后结果集」。自身无状态、无数据库。
批 1 引擎：`postgresql` / `mysql` / `trino` / `starrocks`（StarRocks 复用 MySQL
方言改写产物）；Hive / SparkSQL 为批 2 范围（方言 profile、元数据枚举与执行器
另立 spec）。

### POST /api/v1/query

请求（`X-Api-Key` 鉴权）：

```json
{
  "instance": "pg_prod",
  "sql": "SELECT phone FROM customer",
  "user": "alice",
  "groups": ["devs", "ops"],
  "maxRows": 1000,
  "includeRewrittenSql": false
}
```

- `instance`、`sql` 必填，仅单条语句；`user`/`groups` 是查询主体（上游声明值，
  缺省匿名主体，仅 `*` 策略命中）；`maxRows` 高于硬上限（10000）时钳制执行，
  不报错；`includeRewrittenSql: true` 时响应回显改写后 SQL（调试用）。

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

- `rows` 按列位置对齐；`truncated: true` 表示命中行数上限被截断（不是错误）；
  `masked` / `rowFiltered` 如实标注这条语句发生了什么。

### 错误码

业务错误统一 HTTP 400（`{code, message}`）；鉴权失败 401；容器兜底超时
（`onTimeout` → 取消语句）返回 503。

- mask-query 自有：`MULTI_STATEMENT`、`WRITE_STATEMENT`（只读数据面，非
  `SELECT` 拒绝）、`INSTANCE_NOT_FOUND`、`INSTANCE_NOT_EXECUTABLE`（YAML 导入
  的实例无连接信息，不可执行）、`QUERY_BUSY`（该实例并发已达上限，快速失败）、
  `QUERY_TIMEOUT`、`QUERY_ERROR`（引擎执行失败，message 带 SQLState，不含凭据）、
  `REWRITE_SERVICE_UNAVAILABLE`（mask-core 不可达，fail closed）、
  `CREDENTIAL_UNAVAILABLE`（passwordRef 环境变量缺失）、`UNSUPPORTED_ENGINE`、
  `CONFIG_ERROR`；
- 改写阶段透传 mask-core：`CONFIG_ERROR` / `PARSE_ERROR` / `VALIDATION_ERROR` /
  `UNSUPPORTED_STATEMENT` / `LINEAGE_UNKNOWN` / `REWRITE_ERROR` /
  `METADATA_INSTANCE_NOT_FOUND` / `METADATA_SERVICE_UNAVAILABLE` /
  `POLICY_SERVICE_UNAVAILABLE`。

### 配置项（前缀 `query.`）

| 配置 | 缺省 | 说明 |
|---|---|---|
| `query.timeout-seconds` | 30 | 单语句超时（纯服务端配置，请求级不开放超时覆盖） |
| `query.max-rows` | 1000 | 请求未传 maxRows 时的生效值 |
| `query.max-rows-hard` | 10000 | maxRows 钳制硬上限 |
| `query.fetch-size` | 500 | 流式读取批大小 |
| `query.max-concurrent-per-instance` | 10 | 每实例并发信号量（超出快速失败 `QUERY_BUSY`） |

### 环境变量

- `SQLMASK_QUERY_API_KEY`：本服务 `X-Api-Key` 校验 Key，**未配置 = 全 401**
  （fail closed——这是直接导出真实数据的服务）；
- `SQLMASK_METADATA_BASE_URL` / `SQLMASK_METADATA_API_KEY`：mask-metadata
  地址与 Key；
- `SQLMASK_REWRITE_BASE_URL` / `SQLMASK_REWRITE_API_KEY`：mask-core 按实例
  改写端点地址与可选 Key（未配置放行）；
- 数据库密码沿用 passwordRef 约定：实例只登记环境变量名
  `SQLMASK_DS_<INSTANCE>_PASSWORD`，执行前由本服务本地解析；密码不落库、
  不进日志、不进错误消息。

### 引擎侧 UDF 部署前提

列脱敏用「引擎内 UDF」，改写产物直接可执行的前提是各引擎侧已安装脱敏函数
——这是部署前提，不是本服务职责：

| 引擎 | UDF 安装方式 |
|---|---|
| PostgreSQL | `CREATE FUNCTION` 建 SQL/PL/pgSQL 函数 |
| MySQL | `CREATE FUNCTION` 建存储函数 |
| Hive / Spark | 上传 JAR 并注册函数（批 2 随方言一并交付） |
| StarRocks | StarRocks 3.x Java UDF |
| Trino | Java 插件（SPI 函数包，部署进 plugin 目录后重启） |

各引擎最小 DDL 与全链路验收步骤见 `docs/query-acceptance/golden-queries.md`。

### 构建运行

```bash
mvn -pl mask-query -am package
java -jar mask-query/target/mask-query-0.1.0-SNAPSHOT.jar
```

### 全链路 IT 运行方式

```bash
mvn -pl mask-query -am test -Dtest=QueryEndToEndIT -Dsurefire.failIfNoSpecifiedTests=false
```

嵌入式 PostgreSQL 真库 + 真 UDF 脱敏的完整链路验证（HTTP 入口 → 改写 →
执行 → 护栏 → 审计），约 2-3 分钟。

## UDF 注册表（策略服务）

策略微服务的实例可登记脱敏 UDF 签名（名称 + 有序参数类型 + 返回类型，
首参数绑定被脱敏列的值，对齐 PG 以「名字+参数类型」标识函数、同名重载
按调用点解析）。REST：`POST/GET /api/instances/{instance}/udfs`、
`GET/PUT/DELETE /api/instances/{instance}/udfs/{name}`（web 应用内置
InMemory 存储，部署侧可换 JdbcPolicyStore + schema.sql 的 instance_udf 表）。

DATAMASK 策略写入时按注册表校验：UDF 存在、参数个数（= arguments + 1
个列值）、标量类型（number→整数/浮点/numeric 族，string→字符族，
boolean→boolean，无跨族转换）、每个选中列的类型与某重载首参精确相等
（无隐式转换，需要 `mask_phone(bigint, …)` 这类重载）。删除或替换使
启用中策略失效的 UDF 会被拒绝（先禁用策略）。YAML/CLI 路径不受影响。

## 策略服务管理面（REST）

实例与策略的管理全流程已可通过 REST 编排：`POST /api/instances`（带表列
资源）→ `POST /api/instances/{i}/udfs`（注册脱敏函数签名）→ `POST
/api/instances/{i}/policies`（datamask/row_filter，`subjects` 声明
users/groups 主体，`*` 为全体）→ `GET /api/effective/{i}?user=&groups=`
按主体拉取编译后的生效配置（无参数=匿名主体，仅命中 `*` 策略）。表列
资源可从元数据服务一键导入：`POST /api/instances/{i}/import-metadata`。
同表同类型策略的重叠校验按主体相交放宽——不同人群可各配各的脱敏列与
行过滤。

鉴权：环境变量 `SQLMASK_ADMIN_API_KEY`（管 `/api/instances/**`）与
`SQLMASK_DATA_API_KEY`（管 `/api/effective/**`）配置后强制
`X-Api-Key` 校验（401），未配置则放行（本地开发）；存量策略自动等价
`{"users":["*"]}` 全体生效。
