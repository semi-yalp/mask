# Task 9 报告:WITH/CTE、INSERT、CREATE TABLE AS、INSERT OVERWRITE 语句层 + engine.Classify

- **状态:DONE_WITH_CONCERNS**(简报 Step 1 清单逐条落地、全绿;实现面与 jar 实测逐条校准,若干**有据偏差/差异**集中列于 §4 watchlist,建议控制者过目 §4.1 与 §4.6)
- **提交:见 git log `feat(parser,engine): WITH/INSERT/CTAS/INSERT OVERWRITE 语句层与 classify 移植`**(分支 `go`,基线 ded3184)
- **测试:`go build ./... && go vet ./... && go test -count=1 ./...` 全绿**(ast/dialect/engine/lexer/maskerr/parser/split 全 ok);`gofmt -l` 本任务文件零告警(split 两文件为既有未格式化,未动);上游签名零改动(ParseQuery/ast/dialect/lexer/maskerr 均未动签名)
- **TDD**:先写 stmt_test.go + classify_test.go(简报 Step 1 清单逐条 + 关键字表锁定;红:engine 包缺 `Classify`、parser 缺 `javaCCKeyword` 编译失败)→ 实现 → 绿;另以临时差分抽查文件(与 jar Probe2 输出逐条对照 13 个边界形态)验证后删除

## 1. 交付物

| 文件 | 说明 |
| --- | --- |
| `parser/stmt.go`(新) | `ParseStatement()` 完整入口(替换 T6 存根):SELECT/WITH/VALUES/VALUE/`(` → ParseQuery;INSERT[+OVERWRITE];CREATE;其余 JavaCC keyword 表内首词 → PARSE_ERROR(认识但不实现);表外 token → PARSE_ERROR。`parseWith`/`parseWithItem`(WITH/CTE)、`parseValues`/`parseValuesRow`(VALUES 行集)、`parseInsert`/`parseInsertColumnList`/`insertColumnListAhead`、`parseInsertOverwrite`、`parseCreate`;`javaCCKeyword` 表(729 词,机械提取自 testdata/tokens.json,测试双向锁定) |
| `engine/classify.go`(新) | `Classify(stmt ast.Statement, ordinal int) error` + 内部 `isQuery`/`unsupported`/`unsupportedCreateTableVariant`/`sqlKindName`,逐字镜像 Java `AbstractCalciteDialectAdapter` 的 classify/isQuery/unsupported 三方法 |
| `parser/stmt_test.go`(新) | 简报 Step 1 清单逐条 + 源形态/拒收面/方言门/关键字表锁定,13 个测试函数;`ParseStmtStr`/`mustStmt`/`wantStmtError`/`stmtAs` 测试助手 |
| `engine/classify_test.go`(新) | 纯 AST 层接受面/拒收面(unsupported message 逐字格式锁定)/ordinal/CTAS 变体 + 与 parser 集成的端到端分类面 |
| `parser/select.go`(改) | 仅两处挂点:`ParseQuery` 头位加 `WITH` 分支(镜像 QueryOrExpr 可选 WithList);`parseQueryPrimary` 加 VALUES/VALUE 基元分支(镜像 LeafQuery 的 TableConstructor 备选)。T7/T8 文法零改动 |
| `parser/parser.go`(改) | 删除 T6 的 ParseStatement 存根(保留指路注释) |
| `parser/expr_test.go`(改) | `TestStatementStub`(断言存根报错)替换为 `TestParseStatementLexicalErrorPassthrough`(词法错误透传 + 正常语句可解析) |

## 2. 文法落地(全部经 jar 实测校准——复用既有 probe 工具新增 Probe2 跑通 60+ 形态)

- **分发**(镜像 fork `SqlStmt()` 备选序):INSERT OVERWRITE 先于 INSERT;`SELECT|WITH|VALUES|VALUE|(` 交 ParseQuery(五种查询形态 + statement 级限尾);语句末尾必须 EOF(镜像 `SqlStmtEof`;`SELECT 1; SELECT 2` 与 `(SELECT 1) SELECT 2` 均报 PARSE_ERROR,jar 同判;顶层分号由 split 包先行剥离)。
- **WITH/CTE**:`WITH [RECURSIVE] name [(cols)] AS ( query ) {, ...} body`;镜像 `addWith(withList, toTree)`——**With 包住集合运算归约后的查询体**(`WITH c AS (..) SELECT 1 UNION SELECT 2` → With(body=SetOp)),ORDER BY 限尾由 `parseOrderAndTail` 包在最外(`WITH .. UNION .. ORDER BY 1` → OrderBy(With) → Classify nil,jar 实测 classify=nil 同判);RECURSIVE 仅解析记录(决策 2);CTE 名/列名按 `aliasable`(fork SimpleIdentifier 只接受非保留标识符);WITH 只挂 ParseQuery 头位——集合运算操作数位不消费 WITH(`.. UNION WITH ..` jar 实测拒,Go 同拒);CTE 体括号内经 ParseQuery 获得完整查询能力(内含 WITH/集合运算/限尾均 OK)。
- **VALUES**(fork TableConstructor):行构造器 = `( expr {, expr} )` | 裸表达式(`VALUES 1`、`VALUES (1), 2` jar 均接受);`VALUE` 单数拼写按 `Profile.Conformance` 门控(镜像 `isValueAllowed`:DEFAULT=pg/trino 拒,MYSQL_5/LENIENT=mysql/hive/spark 接受,文案逐字 `VALUE is not allowed under the current SQL conformance level`);解析成功即返回 `*ast.Values`,拒收交 Classify(简报对齐规则);`VALUES (1) ORDER BY 1` → OrderBy(Values)(jar 同形)。
- **INSERT INTO**(fork SqlInsert;INSERT 后方言关键词清单为空产生式):INTO 必需(`INSERT t SELECT 1` 两侧均拒);列清单与括号源按 LOOKAHEAD(2) 区分——`(` 后为**非保留标识符**才是列清单(`aliasStopKw` 代理保留字集;`(SELECT 1)` 走括号源、`("c")` 引号列名走列清单);列名允许多段(`t (a.b)` jar 接受);空列清单 `()` 与带类型 `(a INT)` 拒(后者 fork 受 allowExtend 门控,五方言均 false);源为完整 ParseQuery(SELECT/VALUES/集合运算/限尾包装/括号查询/WITH 头均可——jar 实测面全过)。
- **INSERT OVERWRITE**(fork SqlMaskInsertOverwrite,检查点与文案逐字):`AllowInsertOverwrite` 门控在 OVERWRITE 之后立即检查(`INSERT OVERWRITE is not enabled for this dialect`;pg/mysql/trino 全形态报此门,jar 实测同);`[TABLE]` 可选;表名后下一 token 为关键字 PARTITION(含 `(dt = 'a')` 形态)→ `INSERT OVERWRITE ... PARTITION clause is not supported`;表名本身为未引号 DIRECTORY 且后随字符串字面量 → `INSERT OVERWRITE DIRECTORY is not supported`(fork 的 getToken(0) 语义:`INSERT OVERWRITE t DIRECTORY '/x'` 不命中该检查、落入源解析报 PARSE_ERROR,jar 同)。
- **CREATE TABLE**(fork SqlCreate → SqlCreateTable,babel 变体词):`CREATE [OR REPLACE] [MULTISET|SET] [VOLATILE] TABLE [IF NOT EXISTS] name [(col,..)] [AS query]`。变体词位置 jar 实测校准:**REPLACE 必须经 OR 且在 TABLE 前**(`CREATE TABLE REPLACE x` jar FAIL——REPLACE 成了表名;简报测试行 `CREATE TABLE REPLACE x AS SELECT ..` 按实测调整为 `CREATE OR REPLACE TABLE x`,见 §4.2);MULTISET/SET 先于 VOLATILE(`CREATE VOLATILE MULTISET TABLE`、`CREATE OR REPLACE VOLATILE SET TABLE` jar FAIL,Go 同拒);变体词折算进 T5 单个 `Variant` 字段,叠加形态按 REPLACE>MULTISET>SET>VOLATILE 记录(取舍仅影响报错文案,Classify 对非 Plain 一律拒);缺 AS → Query=nil(纯建表,交 Classify 拒);CTAS 源为完整 ParseQuery(`AS VALUES (1)`、`AS WITH .. SELECT ..` jar 接受,Go 同)。

## 3. engine.Classify(逐字镜像 + 简报裁定项)

- `SELECT`/`INSERT`(含 InsertOverwrite,Java kind 恒为 INSERT)→ nil;`ORDER_BY`/`WITH` → 被包体 `isQuery`(逐字:仅 SELECT|WITH|ORDER_BY,**不含 VALUES/SetOp**)才接受,否则按被包节点 kind 拒 → `VALUES (1) ORDER BY 1` 与 `SELECT 1 UNION SELECT 2 ORDER BY 1` 均 UNSUPPORTED(jar 实测 classify 输出一致)、`WITH .. UNION .. ORDER BY 1` → OrderBy(WITH) → nil(jar 同)。
- 顶层 `Values`/`SetOp` 一律 UNSUPPORTED_STATEMENT(Java default 分支;简报对齐规则)。
- `CREATE_TABLE`:Query==nil → UNSUPPORTED(message 逐字 `statement N: unsupported statement kind CREATE_TABLE; only SELECT and WITH ... SELECT queries are supported in this version`);**Variant!=Plain → UNSUPPORTED**(简报裁定:Java 的变体检查在 composeWriteStatement,Go M1 无 compose 阶段前置到 Classify;错误码一致,message 另述单个 variant 名,如 `statement 0: unsupported CREATE TABLE variant REPLACE; only plain CREATE TABLE ... AS SELECT is supported in this version`)。Query==nil 优先于变体检查(镜像 Java classify 的唯一分支;`CREATE MULTISET TABLE x` 报 CREATE_TABLE,jar 同)。
- message 中 kind 名逐字用 Java SqlKind 名:VALUES/UNION/INTERSECT/EXCEPT/CREATE_TABLE(由 `sqlKindName` 映射)。

## 4. T11 差分 watchlist(本任务新增; jar 实测定位)

1. **【错误码】认识但未实现的语句首词一律 PARSE_ERROR(简报决策)**:jar 实测其中 `UPDATE/DELETE/MERGE/TABLE t/SET x=1/DESCRIBE t/CALL proc(1)` 能解析到 classify 报 **UNSUPPORTED_STATEMENT**,Go 报 PARSE_ERROR——差分护栏实测这些首词时按错误码比对将出现不一致项,记入契约(`GRANT/EXPLAIN/ALTER/TRUNCATE` 等 fork 文法即拒,两侧码一致)。
2. **【简报测试行修正】`CREATE TABLE REPLACE x AS SELECT ..` 在 jar 实测为 FAIL**(REPLACE 解析为表名,后续 `x` 成残片);合法 REPLACE 变体形态为 `CREATE OR REPLACE TABLE x AS SELECT 1`(jar ops[0]=TRUE)。测试按实测语法落地,Variant=Replace + Classify UNSUPPORTED_STATEMENT 的简报意图不变。
3. **【变体检查前移】**`CREATE OR REPLACE/VOLATILE/SET/MULTISET TABLE x AS SELECT ..`:Java classify 接受(变体检查在 compose 阶段),Go Classify 即拒——最终错误码一致(UNSUPPORTED_STATEMENT),报错阶段与文案不同。
4. **【边界】CTAS 列清单**:Go 解析名称形态 `(a, b)`(AST 无类型位)——jar 实测**要求带类型**(`(a, b)` 拒、`(a INT)` 接受),Go 恰好反向(`(a,b)` 接受、`(a INT)` 拒);两侧均有接受/拒绝差异,记契约。
5. **【边界】IF NOT EXISTS**:`CREATE TABLE IF NOT EXISTS x AS SELECT 1` 解析接受,但 ast.CreateTable 无字段承载(compose 再生成时丢失该标记;fork compose 会复现 ifNotExists)。
6. **【边界】WITH 体必须为 Query**:Go AST 的 `With.Body` 为 Query 类型,`WITH c AS (SELECT 1) 1+2`/`WITH c AS (SELECT 1) TABLE c`(显式表)等表达式体 Go 在解析层报 PARSE_ERROR,Java 解析接受、classify 报 UNSUPPORTED_STATEMENT——错误码差异记契约。
7. **【边界】INSERT 源的裸表达式形态**:Java `INSERT INTO t 1+2`/`(NULL)` 等源位表达式可解析(classify 因 kind=INSERT 放行,后续 validate 失败);Go 源位要求 Query,直接 PARSE_ERROR。语料零命中,概率极低。
8. **【形态】VALUES 行构造器**:`VALUES ROW (1)` 两侧均接受——Java 走 RowConstructor 的 ROW 调用,Go 走裸表达式分支解析为普通函数调用(接受面一致,AST 形态略异);`VALUES (DEFAULT)` 两侧均接受——Go 将 DEFAULT 按非保留标识符解析(Java 为 DEFAULT 关键字操作数)。
9. **【形态】`CREATE TABLE SET AS SELECT 1`(保留字作表名)**:Go parseTableIdentifier 不查保留字,表名接受为 "set";jar 中 SET 为独立 token 不可作表名。仅在紧随变体词形态命中,概率极低。

## 5. 顾虑

1. **watchlist §4.1 的错误码差异是简报决策的既定产物**(M1 不实现 UPDATE/DELETE/MERGE 的解析,统一 PARSE_ERROR),T11 契约差分将实测记录;若控制者希望对齐 Java 的 UNSUPPORTED_STATEMENT,需要 Go 侧为这些语句引入占位解析,改动面较大,建议维持现状。
2. **`Classify` 对 `CREATE TABLE x AS SELECT 1 UNION SELECT 2`(Query=SetOp)返回 nil**——逐字镜像 Java classify(Query!=nil 即放行),SetOp 源的最终拒绝发生在 Java 校验/改写后段,M2 对应阶段需保持同判(本任务端到端实测确认 Go 侧亦 nil)。
3. **VALUE 单数门控读 `Profile.Conformance`**(Default 拒/MYSQL_5·Lenient 接受)——五方言实值与 jar 实测一致;未来新增方言时需按 `isValueAllowed` 校准。

## 6. 其他实现决策

- **`javaCCKeyword` 为 729 词全集**(机械提取 tokens.json keywords,`TestJavaCCKeywordTableLocked` 双向锁定,lexer opTable 同法):仅用于语句首词分发的文案分路;SELECT/WITH/VALUES/VALUE/INSERT/CREATE 在分发表内先行命中,永不落该分支;表外词(如 USE、DIRECTORY)走「不认识」分路——两路均 PARSE_ERROR,文案区分仅助诊断。
- **WITH 挂 ParseQuery 头而非 parseQueryPrimary**:保证集合运算操作数位不接受 WITH(fork LeafQuery 同判),且子查询/派生表/INSERT 源/CTAS 体经同一入口自动获得 WITH/VALUES 能力(T7 预留注释兑现)。
- **`parseWith` 的限尾**:body 经 `parseQueryPrimary`+`parseSetOpExpr` 归约后包 With,再交 `parseOrderAndTail`(TOP fetch 并入包装——AllowTopN 开启时才可达,五方言均关)。
- **引擎包落位**:`engine` 为新 Go 包(仅 ast+maskerr 依赖);classify_test 以测试内 import 方式引用 parser 做端到端,不产生依赖环。
- **Pos 口径**:Insert/InsertOverwrite.Pos = INSERT token;With.Pos = WITH token;CreateTable.Pos = CREATE token;TableNameRef.Pos = 首段 token。INSERT OVERWRITE 门控错误取语句首 token 位(fork 该错误不带位点)。

---

# Fix Round 1 报告:首词 classify 边界 / CTAS 类型化列 / 变体检查回位 compose

- **状态:DONE**(四项裁定全部落地;`go build ./... && go vet ./... && go test -count=1 ./...` 全绿;临时差分抽查与 jar Probe3/Probe4/Probe5/Probe6 逐条对照后删除)
- **提交:`fix(parser,engine,ast): 首词 classify 边界/CTAS 类型化列/变体检查回位 compose(fix round 1)`**
- **jar 实测(新增 Probe3/Probe4/Probe5/Probe6,五方言 conformance 一致)**:12 个语句首词的良构/残片形态、CTAS 类型化列 10 形态、7 词的别名/CTE 名/列名位保留性。

## F1. 首词边界(顾虑 1)

- **落地**:ParseStatement 兜底分路改两路——`firstWordUnsupportedKinds`(12 词)命中且过形状护栏 → **UNSUPPORTED_STATEMENT**(message 镜像 classify 格式,ordinal 固定 0,K 用 jar 实测 SqlKind 名);其余 JavaCC keywords 表内首词 → PARSE_ERROR(fork 文法即拒)。
- **词表(kind 名逐字取 jar 实测)**:UPDATE→UPDATE、DELETE→DELETE、MERGE→MERGE、TABLE→EXPLICIT_TABLE、SET→SET_OPTION、DESCRIBE→DESCRIBE_TABLE、CALL→PROCEDURE_CALL(裁定清单 7 词)+ **BEGIN/COMMIT/ROLLBACK/SHOW/DISCARD→OTHER**(裁定"等"字延展:Probe3/Probe4 实测五方言解析成功、classify default → UNSUPPORTED,与 7 词同类;BEGIN/COMMIT/ROLLBACK 裸形与 WORK/TRANSACTION 尾均实测 OK,不做尾部护栏)。
- **形状护栏(firstWordTailShape,过滤 jar 实测 FAIL 的残片)**:DELETE→必须随 FROM;MERGE→必须随 INTO;CALL→名字后必须有 `(`/`.`(`CALL proc` 裸名 jar 实测 FAIL);SHOW/DISCARD→必须随标识符形态项(裸形 jar 实测 FAIL);SET→随非保留标识符且不得为 TRANSACTION(`SET TRANSACTION` jar 实测 FAIL——TRANSACTION 为保留 token,`SET x TO 1`/长形态实测 OK);UPDATE/TABLE→随非保留标识符;DESCRIBE→标识符形态或查询头(SELECT/WITH/VALUES/`(`——`DESCRIBE SELECT 1` jar 实测解析成功 kind=EXPLAIN,Go kind 名仍报 DESCRIBE_TABLE,契约文案可异)。
- **护栏底座增补**:`aliasStopKw` 增补 UPDATE/DELETE/MERGE/TABLE/SET/DESCRIBE/CALL 七词——Probe5/Probe6 实测七词为 fork 保留 token(别名 `SELECT 1 update`、CTE 名 `WITH update AS ..`、INSERT 列名 `(update)` 全部 FAIL),而 BEGIN/COMMIT/ROLLBACK/SHOW/DISCARD 同法实测可作别名故不入集。此增补同时修正 peekAliasable(列清单 LOOKAHEAD 代理)与 CTE 名/列名判定的同窗差异。

## F2. CTAS 类型化列(顾虑 3)

- `parseCreateColumnList` + `skipColumnType`:列清单镜像 fork ColumnWithType——**列必须带类型**;名称(允许多段,`(a.b INT)` jar 实测接受)解析进 ast.Columns,类型以括号深度感知的跳读消费后丢弃(`DECIMAL(5, 2)` 括号内逗号不终结,M3 从 unparse 重组);类型缺失 → PARSE_ERROR 且位置镜像 jar(`(a, b)` 报于逗号位 col 18、`(a)` 报于闭括号位 col 18、`(a INT, b)` 报于 col 25)。
- 已知残留:跳读不校验类型文法,垃圾类型(`a FOO BAR`,jar 拒)Go 放行——记 T11 watchlist。

## F3. IF NOT EXISTS

- `ast.CreateTable` 新增 `IfNotExists bool` 字段(fix round 1 控制者裁定;与 Java SqlCreateTable.ifNotExists 对齐,M3 compose 重组需要),parseCreate 解析记录;ast_test.go 的 `var _ Statement` 断言表同步(Replace 行加 `IfNotExists: true`);正/反例测试落地。

## F4. 变体检查回位 compose(顾虑 3)

- `engine.Classify` 还原为:CreateTable 只查 `Query == nil`(变体不查)——`CREATE OR REPLACE TABLE x AS SELECT 1` 现在通过 Classify(与 Java classify 实测一致)。
- 新增导出 `engine.CheckCreateTableVariantForCompose(stmt ast.Statement) error`(M3 compose 钩子,不接入 M1 判定路径):语义逐字镜像 Java checkCreateTableVariant(:238)——**Replace/Volatile/Multiset 拒、Set 放行**(Java 文案列举含 SET 但检查不拒 SET;`CREATE SET TABLE .. AS SELECT` 在 compose 阶段可过);非 CreateTable 语句 no-op(instanceof 守卫);message 逐字对齐 Java 文案,末尾方言名 "(profile)" 因钩子无 Profile 入参不携带(契约文案可异项)。
- 配套:`parseCreate` 变体折算优先级调整为 REPLACE>VOLATILE>MULTISET>SET——VOLATILE 必须压过 SET,否则 `CREATE SET VOLATILE TABLE ..`(jar 解析接受)折成 Set 会被钩子误放行。
- 端到端测试:五变体 × (Classify 放行 + 钩子裁定) 逐条断言;`CREATE TABLE x`(Query nil)仍报 UNSUPPORTED(CREATE_TABLE)。

## Fix Round 1 新增 watchlist

1. 【窗口】首词护栏未覆盖的残片(如 `COMMIT x`、`BEGIN FOO`、`SET <保留词非 TRANSACTION>`)落 UNSUPPORTED 分路而 jar 为 PARSE_ERROR;良构形态全部对齐。
2. 【窗口】CTAS 类型跳读不校验类型文法:垃圾类型(`a FOO BAR`,jar 拒)Go 放行。
3. 【文案】`DESCRIBE SELECT ..`(jar kind=EXPLAIN)Go 报 DESCRIBE_TABLE;UNSUPPORTED 首词 message ordinal 固定 0(Java 为语句序号)——契约只比 stage+code,文案可异项。
