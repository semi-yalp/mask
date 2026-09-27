# mask-lite-go 独立模块设计（Java mask-lite 的 Go 重新实现）

- 日期：2026-09-27
- 分支：`feature/mask-lite-go`（基于 `go` 分支顶部）
- 模块：仓库顶层 `mask-lite-go/`，`module io.masklite/go`，**独立 Go module**
- 状态：设计定稿，按此实现

## 1. 定位与独立性

Java 侧 `mask-lite` 是 PG-only 的"脱敏+行过滤"改写内核最简抽取：自包含单 jar，
包名 `io.masklite.*`，对 `io.sqlmask` 零依赖。Go 版对应地做成**独立模块**：

- 不 import `io.sqlmask/go` 的任何包（`maskerr`/`dialect`/`split`/`lexer` 等一律自带）；
- 不参考 `go` 分支的 Go 移植总体设计 spec/M1 计划（用户明示要求独立）；
- 唯一第三方依赖：`gopkg.in/yaml.v3`（YAML 语法解析用，schema 校验自研）；
- 契约源 = Java worktree `.worktrees/mask-lite-pg/mask-lite` 的源码/测试 + shaded jar 实证探测。

## 2. 兼容标准

| 维度 | 标准 |
|---|---|
| 功能范围 | 与 Java mask-lite 逐项对齐（见 §4 行为规格） |
| 错误码 | `SqlMaskException.Code` 逐字一致（14 常量全定义，mask-lite 实际触发前 7 个） |
| 错误文案 | 关键消息族逐字一致（MaskLiteTest 断言的消息、`table 'c.s.t': row filter …`、`statement N: ` 前缀规则）；其余文案允许差异但风格一致 |
| 产物 SQL | 语义等价；渲染器按 Calcite PG unparse 风格（子句前换行、零缩进）实现，目标是能与 Java 产物逐文件 diff（差分是开发仪器，不作为验收硬门槛） |
| 验收 | Java `MaskLiteTest` 5 用例移植全绿；TPC-DS 99 条离线改写回归 `99/99`（对照 Java 打印值 15 masked / 91 rowFiltered） |

## 3. 实证锁定的行为（java -jar 探测，2026-09-27）

```
in: SELECT id, phone FROM customer WHERE id > 10        (customer.phone→mask_phone, rowFilter status='active')
out: SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
     SELECT id, phone
     FROM (SELECT *
     FROM customer
     WHERE status = 'active') AS customer
     WHERE id > 10
     ) AS r;

in: WITH x AS (SELECT 1 AS a) SELECT a FROM x
err: statement 1: mask-lite only rewrites SELECT/WITH queries; statement kind 'with' is not supported   (exit 1)

in: INSERT INTO customer SELECT 1, 'x', 'y'
err: statement 1: mask-lite only rewrites SELECT/WITH queries; statement kind 'insert' is not supported (exit 1)

in: SELECT concat(id, phone) FROM customer              (合成列场景)
out: SELECT mask_phone(r.mask_col_1, 3, 4) AS mask_col_1 FROM (
     SELECT concat(id, phone)
     FROM (SELECT *
     FROM customer
     WHERE status = 'active') AS customer
     ) AS r (mask_col_1);

in: SELECT id, status FROM customer                     (无策略命中,仅行过滤)
out: SELECT id, status
     FROM (SELECT *
     FROM customer
     WHERE status = 'active') AS customer;              -- 透传的是【注入后】的渲染文本
```

## 4. 行为规格（移植契约）

### 4.1 门面与管线（`rewrite` 包 + 根包 `masklite`）

- API：`FromYaml(string)` / `FromYamlFile(path)`（IO 失败 → `IO_ERROR`
  `cannot read metadata file '<path>': …`）/ `RewriteStatements(sqlText) ([]StatementRewrite, error)` /
  `Rewrite(sqlText) (string, error)` / `Config()`。
- `StatementRewrite{Ordinal, OriginalSQL, RewrittenSQL, Masked, RowFiltered}`：
  - `OriginalSQL` = 注入**前**解析树的渲染（永不含注入）；
  - `RewrittenSQL` = masked 时为包装 SQL，否则为**注入后**树的渲染；无注入且未 masked 时与 OriginalSQL 相同；
  - `Masked` = 有外层包装；`RowFiltered` = 注入次数 ≥ 1。
- `Rewrite` 拼接：每条 `rewrittenSql + ";"`，语句间 `"\n\n"`，空输入 → `""`。
- 任一语句失败 → 整批失败（无部分结果）；语句级错误补 `statement N: ` 前缀
  （parse 错误自带；registry 构建期 CONFIG_ERROR 无前缀）。

管线（逐语句）：split → parse → 只读门 → 行过滤注入（校验/血缘之前）→ 渲染内层 SQL →
CTE 内联 + 血缘 → 改写计划 → 包装器。

- 只读门：顶层节点 ∈ {SELECT, ORDER BY 包装} 之外一律
  `UNSUPPORTED_STATEMENT`，消息 `mask-lite only rewrites SELECT/WITH queries; statement kind '<lowerKind>' is not supported`
  （裸 WITH 的 kind 是 `with`，实证拒绝；INSERT → `'insert'`）。
- 方言：仅 `postgresql`；`ByName` 其他名 → `CONFIG_ERROR`
  `unsupported dialect 'x'; mask-lite only supports: postgresql`。

### 4.2 词法/拆分/解析（`split`/`lexer`/`parser`）

- 拆分器：移植 Java `SqlStatementSplitter` 语义（顶层 `;`；单引号含 `''`/`E''`、
  双引号、`$$`/`$tag$`、`--`、可嵌套 `/* */` 不产生切点；纯注释/空白段丢弃；保序去尾分号）。
- 词法：PG 单方言——双引号标识符（大小写保留）、未引号标识符折小写、`E''` 转义串、
  dollar 引用、`--`/嵌套块注释、数字（含近似数）、`?`/`?N` 参数、双字符算子（`<> != <= >= || :: =>`）。
- 语法面（以 TPC-DS 99 语料 + MaskLiteTest 为验收语料）：
  - `SELECT [DISTINCT] items`（`*` / `t.*` / 表达式 [[AS] alias]）；
  - FROM：表引用（1–3 段名）、`[AS] alias [(cols)]`、派生表、JOIN
    （INNER/LEFT/RIGHT/FULL/CROSS/NATURAL、ON/USING）、逗号连接；
  - WHERE / GROUP BY（含 `ROLLUP(...)`；语料无 grouping sets/cube，但按同产生式一并支持）/
    HAVING / 窗口 `OVER (PARTITION BY … ORDER BY … [ROWS|RANGE BETWEEN … AND …])`；
  - 集合运算 UNION [ALL]/INTERSECT/EXCEPT（INTERSECT 优先，同级左结合），操作数可括号；
  - 表达式：CASE（两种）、CAST 与 `expr::type`、IN/NOT IN（列表|子查询）、EXISTS、
    BETWEEN [SYMMETRIC]、[I]LIKE/SIMILAR TO [ESCAPE]、IS [NOT] NULL / IS [NOT] DISTINCT FROM、
    标量子查询、`||`、算术、一元 +-/NOT、`COUNT(*)`/`COUNT(DISTINCT x)`/任意函数调用、
    字面量（数、串、布尔、NULL、`DATE/TIME/TIMESTAMP '…'`、`INTERVAL '…' unit [TO unit]`）、`?` 参数；
  - 顶层 ORDER BY/LIMIT/OFFSET/FETCH → ORDER BY 包装节点；`TOP (n)` → PARSE_ERROR；
  - WITH [RECURSIVE]（RECURSIVE 仅解析，血缘期拒绝）；`VALUES` 出现在 FROM/子查询位置；
  - 其他语句首词（INSERT/UPDATE/DELETE/CREATE/…）→ 语句级 UNSUPPORTED_STATEMENT 标记，
    由只读门按 kind 出错（对齐实证：INSERT 不产生 PARSE_ERROR）。

### 4.3 渲染器（`render`）

AST → SQL。风格对齐 Calcite PG unparse：零缩进、子句关键字前换行（`SELECT …` 换行
`FROM …` 换行 `WHERE …`、派生表/子查询内部同样处理）、SELECT 项逗号单空格同行、
未引号标识符小写、双引号标识符按需重引（`[a-z_][a-z0-9_$]*` 且非 PG 保留字 → 裸写，
否则双引号）、字符串字面量 `'…'`（内部 `'` 双写）、数字精确/近似按原词法、`||`/`::` 原样。

### 4.4 行过滤（`rowfilter`）

- `Registry`：对每个带 rowFilter 的表三步构建——①按 `SELECT * FROM c.s.t WHERE <cond>`
  解析（失败 → `CONFIG_ERROR` `table 'c.s.t': row filter cannot be parsed: …`）；
  ②白名单：标识符必须单段且为该表声明列（否则 `… row filter must only reference declared columns of the filtered table; 'x' is not one` / `… must reference the table's columns directly, without qualification`），
  算子白名单 `AND OR NOT = <> < <= > >= + - * / % 一元 +/- IS NULL IS NOT NULL IS [NOT] DISTINCT FROM IN`，
  子查询/动态参数/函数/CAST 一律拒绝（`… must only use columns, literals and basic comparisons; '<op>' is not allowed`）；
  ③列引用可解析性复核（等价二次 validate）。全部 `CONFIG_ERROR`。
- `Rewriter`（注入先于血缘，遍历不改输入树）：
  - 位置：FROM 表引用（裸表/别名/JOIN 操作数）、派生表、表达式内子查询
    （WHERE/HAVING/投影/IN/EXISTS）、集合运算分支、CTE 体；顶层 ORDER BY 包装改写 query 部分；
  - 模板：裸表 `t` → `(SELECT * FROM t WHERE <cond>) AS t`；别名 `t AS a` → 只换 AS 第 0 操作数
    → `(SELECT * FROM t WHERE <cond>) AS a`（列别名清单保留）；每个引用位独立构造（不共享子树）；
  - CTE 作用域栈：可见 CTE 名优先于基表；register-after-rewrite（body 改写完才入 scope）；
  - 名字解析：1 段名 → CTE 优先 → 匹配多个声明表 → `VALIDATION_ERROR`
    `unqualified table reference 'x' matches multiple declared tables [c.s.t, …]`（排序）；
    匹配 1 个且受控 → 注入；3 段名精确匹配；2 段名 PG 跳过；
  - fail-closed：白名单外 FROM 形态（UNNEST/LATERAL 等）若子树引用受控表 → `UNSUPPORTED_STATEMENT`
    `unsupported FROM clause shape <KIND> involving filtered table 'c.s.t'; the row filter cannot be injected safely`；
    注入后语句出现 4 段列引用且前三段匹配受控表 → `UNSUPPORTED_STATEMENT`
    `statement references filtered table 'c.s.t' with fully qualified columns, which an injected row filter would break; alias the table and qualify columns with it instead`；
    派生表构建失败 → `UNSUPPORTED_STATEMENT` `cannot build a filtered derived table for 'c.s.t': …`。

### 4.5 血缘（`lineage`，AST 级）

- CTE 内联：非递归 CTE 内联为派生表（PG 作用域：内层遮蔽外层、只见前序兄弟）；
  递归 CTE → `LINEAGE_UNKNOWN` `cannot trace lineage through recursive CTE 'x' …`；
  `ORDER BY(WITH)` 归一后处理。
- 作用域与名字解析：FROM 别名/表名 → 声明表 3 段路径；未引号已折小写，比较大小写敏感；
  未限定列歧义/不可解析 → `VALIDATION_ERROR`（`validation failed: …`）；
  JOIN USING 列 = 两来源并集；`SELECT *`/`t.*` 按声明列展开（q88 用到 `SELECT *`）。
- 输出列 origins（逐列）：
  - 列引用 → {(c.s.t.col, derived=false)}；表达式 → 操作数 origins 并集（derived=true 传播）；
  - 字面量/参数 → 空集 = NO_ORIGIN → 透传；
  - 聚合/标量函数 → 参数 origins 并集；`COUNT(*)` → 空集；
  - 集合运算 → 各分支对应输出列 origins 并集；`(VALUES …)` 派生表列 → 空集；
  - **标量子查询两阶段**：①对投影中每个子查询递归做安全校验——子查询输出列 origins 为空
    或不命中任何策略 → 安全；命中或无法证明 → 整语句全列 `LINEAGE_UNKNOWN`（fail-closed）；
    ②安全后，把子查询位视作 NULL 重取 origins（= 表达式 origins 计算跳过子查询子树）：
    纯子查询列 → NO_ORIGIN 透传；混合列 → 保留真实来源正常包装；
  - 任何未知构造 → `LINEAGE_UNKNOWN`
    `output column N ('name') has no safely traceable origin; cannot rewrite this statement`（N 1 起）。
- 多来源掩码决策（`policy` 包 PDP）：origins 中命中绑定策略者按规范化列 key
  （catalog.schema.table.column 字典序）取最小（首现胜平局）；无一命中 → 不包装。

### 4.6 列脱敏包装（`rewrite`）

- 模板（与实证逐字对齐）：
  `SELECT <items> FROM (\n<inner>\n) AS r`；命中列 `<udf>(r.<name>, <args…>) AS <name>`，
  未命中列 `r.<name>`（不加别名）；UDF 名与参数按标识符/字面量渲染规则，绝不字符串拼值。
- 参数字面量：bool → TRUE/FALSE；整数 → 精确十进制；浮点 → 近似数；字符串 → `'…'`（`'` 双写）；
  其他类型（YAML map/list）→ 加载期 `CONFIG_ERROR`。
- 无名输出列：合成名 `EXPR$N`（N=1 起按位置）；需包装时改名 `mask_col_N` 并在 `) AS r` 后追加
  位置别名清单 `(n1, n2, …)`（覆盖全部输出列，按标识符渲染规则）；
  内层 SQL 保持不变。
- 重复输出名（大小写不敏感）且需包装 → `REWRITE_ERROR`
  `cannot wrap a query whose output contains duplicate column name 'x': the wrapper could not reference the right column unambiguously in dialect 'postgresql'`。
- 无任何输出列命中策略 → 直接返回注入后渲染（不包装）。

### 4.7 配置（`config` + `metadata`）

- legacy YAML schema（Java `YamlConfigLoader` 对齐）：
  `metadata.tables[]`（catalog/schema/name 必填非空白、`rowFilter?`（blank→无）、
  `columns[]`（name/type 必填）非空）、`policies`（名→{udf 必填, arguments?}）、
  `columns[]` 绑定（catalog/schema/table/column/policy 必填、`inheritOnCopy?` 默认 false、
  policy 必须已声明）。
- 错误：YAML 语法 → `CONFIG_ERROR` `<source>: invalid YAML: …`；校验错误带 YAML 路径
  （`metadata.yaml: metadata.tables[0].columns[2].type: …`）；
  重复键检测（表键/列名/绑定键，规范化后）→ `CONFIG_ERROR` `duplicate table 'c.s.t'` 等；
  `unknown policy 'x' (declared policies: […])`。
- 规范化：身份名 trim+lowercase；类型词表对齐 `PostgresqlTypeResolver`
  （bool 族/int2/4/8、real/float4、double/float8、decimal|numeric(p[,s])（(p)≡(p,0)）、
  char|character(n)（缺省 1）、varchar|character varying(n)、text、date、
  timestamp[(p)]、timestamptz|timestamp with time zone[(p)]、time[(p)]、timetz[…],
  未知 → `CONFIG_ERROR` `unsupported or malformed type declaration '<raw>'; supported scalar types: …`）。
  类型仅用于 schema 声明与校验，不驱动改写。
- YAML 解析用 yaml.v3 的 `yaml.Node`（保留结构做路径级报错与重复键检测），schema 走查自研。

### 4.8 CLI（`cmd/masklite`）

- `--metadata <yaml>`（必填）、`--sql <text>` 或 `--input <file>` 二选一；未知参数/缺参/二选一违反 →
  usage 至 stderr + exit 2（`unknown argument: <arg>`）；
- stdout：每语句一行 `rewrittenSql;`；诊断走 stderr；
- 退出码：0 成功；1 = 领域错误（stderr 打 `message`）或意外错误（`fatal: <msg>`）；2 用法错误。

## 5. 包结构与依赖方向

```
mask-lite-go/
  go.mod (io.masklite/go; gopkg.in/yaml.v3)
  maskerr/      错误码与 Error（无依赖）
  dialect/      PG Profile + ByName 拒绝（→ maskerr）
  split/        语句拆分（无依赖）
  lexer/        PG 词法（→ dialect, maskerr）
  ast/          AST 节点（无依赖）
  parser/       递归下降解析（→ lexer, ast, dialect, maskerr）
  render/       AST→SQL（→ ast）
  metadata/     TableMetadata/Column/ColumnKey/类型词表（无依赖）
  config/       YAML 加载与校验（→ metadata, maskerr；yaml.v3）
  policy/       legacy 绑定→策略、PDP 决策（→ metadata, config, maskerr）
  rowfilter/    白名单 registry + 注入 rewriter（→ ast, parser, metadata, maskerr）
  lineage/      CTE 内联 + 作用域 + origins + 两阶段子查询（→ ast, metadata, policy, maskerr）
  rewrite/      管线编排/计划/包装器（→ 上述全部）
  masklite/     门面 FromYaml/Rewrite/…（→ rewrite, config, dialect, split）
  cmd/masklite/ CLI main（→ masklite）
  testdata/     tpcds 语料（99 查询 + tpcds-*.yaml 自包含复制）
```

## 6. 测试与验收

1. 各包表驱动单测（Go 惯例：`testing` 标准库、`t.Run` 中文用例名、`t.Helper()`）。
2. Java `MaskLiteTest` 5 用例移植（断言消息逐字：`mask_phone(r.phone, 3, 4) AS phone`、
   `WHERE status = 'active'`、`only rewrites SELECT/WITH queries`、
   `mask-lite only supports: postgresql`、`row filter` 族、`rewrite()` 分号计数=2）。
3. 行为单测：注入位置全覆盖（JOIN/派生表/子查询/集合分支/CTE 遮蔽/同名表多处）、
   白名单拒绝族、fail-closed 三处、递归 CTE、重复输出名、合成列、多来源 tie-break、
   两阶段标量子查询（安全透传/混合包装/命中即拒）、YAML 错误路径与类型词表边界。
4. TPC-DS 回归：99 文件 + `tpcds-both.yaml`，断言 success==99、masked>0、rowFiltered>0、
   两者交集非空（镜像 Java 硬断言），打印 `TPC-DS offline rewrite: N/99 success, M masked, K rowFiltered`。
5. 差分仪器（不作为门槛）：同一 jar 对 99 条产出的渲染文本与 Go 产物逐文件 diff，偏差记录进
   `docs/differential-notes.md`（语义等价即放行）。

## 7. 明确不做

- 五方言（仅 postgresql，`ByName` 其余拒绝）；JDBC 元数据采集；写语句改写；
  Ranger 式 policies.yaml 与主体维度；HTTP 服务；`ClassloaderIT`/远程 PG IT 的等价物
  （Go 无 classloader 场景；远程执行验证超出本模块范围）。
