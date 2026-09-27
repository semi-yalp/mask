# Go 版移植 M1(解析器与 AST)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `go` 分支落地 Go 版 M1:语句拆分器、五方言词法、SQL AST、手写递归下降解析器(四形态语句 + 全表达式)、classify 移植,并用 Java 版 CLI 建立解析期接受/拒绝差分护栏。

**Architecture:** 单 Go module(`io.sqlmask/go`),包按内核分层(`maskerr`/`dialect`/`split`/`lexer`/`ast`/`parser`/`engine`/`contract`);方言差异全部表达为 `dialect.Profile` 配置;解析器按 Calcite fork(mask-sqlparser)的语法语义移植;与 Java 的等价性用"语料 → Java CLI 判定 → Go 判定"的契约差分验证。

**Tech Stack:** Go 1.27(stdlib only,M1 零第三方依赖)、JUnit/Java 21 仅作为差分参照(`mask-core/target/sql-mask.jar` 已构建)。

**Spec:** `docs/superpowers/specs/2026-09-27-go-port-design.md`(§2 里程碑 M1、§3 解析器、§4 AST、§10 测试与验收;本计划实现 spec 的 M1,后续 M2–M5 另行出计划)

## Global Constraints

- 工作分支:`go`。所有提交在 `go` 分支;提交信息用 conventional commits(fix/feat/test/docs/chore + scope,如 `feat(parser): ...`)。
- Go:1.27(本机已装 go1.27.1 windows/amd64);module 路径固定 `io.sqlmask/go`。
- M1 零第三方依赖:只用标准库(yaml.v3 等属 M4)。`go.sum` 不应出现。
- 错误码与 Java 完全一致,逐字使用:`CONFIG_ERROR, PARSE_ERROR, VALIDATION_ERROR, UNSUPPORTED_STATEMENT, LINEAGE_UNKNOWN, REWRITE_ERROR, IO_ERROR, POLICY_SERVICE_UNAVAILABLE, POLICY_INSTANCE_NOT_FOUND, INTROSPECT_ERROR, METADATA_INSTANCE_NOT_FOUND, METADATA_INSTANCE_EXISTS, METADATA_CREDENTIAL_UNAVAILABLE, METADATA_SERVICE_UNAVAILABLE`。错误文案可异。
- 兼容标准 = 语义等价(spec §1.1):不追求与 Calcite 文本逐字节一致;追求**解析期接受/拒绝边界**与 Java 一致(错误码一致)。
- 五方言注册名与顺序(错误信息引用时逐字):`postgresql, trino, mysql, hive, sparksql`。
- 每个任务收口标准:`go build ./... && go vet ./... && go test ./...` 全绿后提交。
- Java 参照:`mask-core/target/sql-mask.jar`(仓库内已构建,Java 21);CLI 错误输出格式为 stderr 上的 `sql-mask: [CODE] message`,退出码 1(方言不存在为 2)。
- 仓库根定位:Go 测试/工具从自身路径向上找 `go.mod`(该文件在仓库根),得到 repoRoot,再拼 `mask-engine/...` 相对路径。不复制语料文件——直接读原文件。
- 环境是 Windows + Git Bash:`go test` 直接可用;Go 的 `os/exec` 调 java 不受 MSYS 路径转换影响(不要在 shell 里给 java 传 `/xxx` 形参)。

## M1 解析器范围决策(写码前必读)

以下决策把 spec §3/§4 落到语法与 AST 层,后续任务的实现都以此为准:

1. **语句形态**:解析并接受 SELECT、WITH..query、INSERT INTO..query(含 VALUES 源)、CREATE TABLE [变体] AS query、INSERT OVERWRITE [TABLE]..query(仅 `AllowInsertOverwrite` 方言);top-level `ORDER BY / LIMIT / OFFSET / FETCH` 生成 `ast.OrderBy` 包装节点(镜像 Calcite SqlOrderBy)。其余 → `engine.Classify` 报 `UNSUPPORTED_STATEMENT`(镜像 Java `AbstractCalciteDialectAdapter.classify`)。
2. **`WITH RECURSIVE`**:语法层接受(Java 也解析成功),由后续阶段拒绝(语料 `tpcds_unsupported.sql` 注明"第一版明确不支持",Java 在 CTE 内联/校验阶段报错)。M1 差分只对齐**解析期**判定,该用例 Java 端到端码为后端阶段,Go 端 M1 视为"解析接受"。
3. **关键字识别**:未加引号的词按 `strings.EqualFold` 对关键字匹配(引号内永不匹配);未命中关键字的词成为标识符。标识符**值**的大小写按 `UnquotedCasing`/`QuotedCasing` 在解析时折算(PG/Trino/Hive/Spark 未加引号转小写,MySQL 原样;引号内两种方言都保持原样)。
4. **保留字问题**:JavaCC 生成的 `SqlMaskParserImplConstants.tokenImage` 含全部关键字词表(Task 4 机械提取为 `testdata/tokens.json`,词法测试逐个对照);"非保留关键字可作标识符"的完整清单不可机械提取,M1 维护最小集合 `TOP OVERWRITE YEAR MONTH DAY HOUR MINUTE SECOND`(可作表别名/列别名/标识符),其余冲突由 Task 13 语料差分按"发现一个→加一个→带用例固化"的协议处理(协议本身是明确流程,不是待定项)。
5. **M1 不支持的构造**(词法或语法层即 `PARSE_ERROR`,与 Java 的差分结果记入契约;若差分证明 Java 接受且语料需要,再按协议补):`UNNEST`/`TABLESAMPLE` 表函数、`GROUPING SETS/ROLLUP/CUBE`、`FILTER (WHERE ...)` 聚合子句、`::` 强转、`->` 等非 Calcite 标准算子、`LATERAL`(先不解析)、`X'..'` 二进制字面量、命名的 `WINDOW` 子句以外的窗口语法(内联 `OVER (...)` 必须支持)。
6. **字面量**:整数/小数/近似数(`123.45e6`)、字符串(`'...'`,`''` 转义;`E'...'` 按 PG 转义字符串词法接受,差分验证 Java 行为)、布尔、NULL、`DATE '...'` / `TIME '...'` / `TIMESTAMP '...'` / `INTERVAL '...' unit [TO unit]`、动态参数 `?` 与 `?1`。
7. **表达式优先级链**(从低到高,镜像 Calcite):`OR` → `AND` → `NOT` → 谓词层(`= <> != < <= > >=`、`IS [NOT] NULL/TRUE/FALSE/UNKNOWN`、`[NOT] IN (列表|子查询)`、`[NOT] BETWEEN [SYMMETRIC|ASYMMETRIC]`、`[NOT] (ILIKE|LIKE|SIMILAR TO)`、`IS [NOT] DISTINCT FROM`)→ 加减与 `||` → 乘除模 → 一元 `+ -` → 后缀/primary(字面量、标识符、函数调用、CASE、CAST、EXISTS、标量子查询、`(expr)`、`*`(仅 COUNT(*) 与 SELECT item))。函数调用支持 `DISTINCT` 聚合、`COUNT(*)`、窗口 `OVER (PARTITION BY .. ORDER BY .. [ROWS|RANGE ..])`。
8. **类型名(CAST 用)**:`BOOLEAN, INTEGER/INT, BIGINT, SMALLINT, TINYINT, REAL, FLOAT[(n)], DOUBLE PRECISION, DECIMAL/DEC/NUMERIC[(p[,s])], CHAR[ACTER]/VARCHAR[(n)]/CHARACTER VARYING, DATE, TIME[(p)], TIMESTAMP[(p)]`(TIME/TIMESTAMP 可带 `WITH|WITHOUT TIME ZONE`),`BINARY/VARBINARY[(n)]`,`INTERVAL` 单元;其余类型名 → PARSE_ERROR,差分后扩充。
9. **CREATE TABLE 变体**:语法层接受前导变体词 `REPLACE|VOLATILE|SET|MULTISET` 记入 AST;`engine.Classify` 对非 plain 变体报 `UNSUPPORTED_STATEMENT`(MySQL 行为;其他方言若差分显示接受,按契约文件记录差异并回归 spec 决策)。
10. **INSERT OVERWRITE**:`PARTITION`/`DIRECTORY` 子句 → `PARSE_ERROR`(镜像 mask 语法在产生式里的拒绝);`INSERT OVERWRITE` 在未开放方言 → `PARSE_ERROR`。

## 差分护栏语义(本计划的"验收=绿"的定义)

- Java 侧判定 = 对语料逐语句运行 `java -jar mask-core/target/sql-mask.jar --metadata <语料同名yaml> --sql <stmt> --dialect <d>`:
  - 退出 0 → 端到端接受(必然解析接受);
  - stderr 含 `[PARSE_ERROR] statement N: parse error` → 解析期拒绝;
  - stderr 含 `[UNSUPPORTED_STATEMENT] statement N: unsupported statement kind` → classify 期拒绝;
  - 其余码(VALIDATION_ERROR/LINEAGE_UNKNOWN/CONFIG_ERROR…)→ "后端阶段失败",M1 视为解析接受。
- Go 侧判定 = `split` + `parser.ParseStatement` 成功与否 + `engine.Classify` 结果(PARSE_ERROR/UNSUPPORTED_STATEMENT/接受)。
- **M1 绿 = 对全部语料、每个语句、其目标方言:Go 的解析期判定与 Java 推导的解析期判定一致**,差异清单为空。语料与方言映射:文件名含 `mysql`→mysql、含 `trino`→trino、其余→postgresql;hive/spark 由 Task 13 新建的小语料覆盖。

---

### Task 1: 模块骨架与错误码包 maskerr

**Files:**
- Create: `go.mod`
- Create: `cmd/sqlmask/main.go`
- Create: `maskerr/errors.go`
- Test: `maskerr/errors_test.go`
- Create: `internal/repotool/root.go`(repoRoot 定位,后续任务复用;`internal` 因仅被测试与工具用)

**Interfaces:**
- Produces: `maskerr.Code`(string 别名,14 个常量,值与 Java 逐字)、`maskerr.Error{Code Code; Message string; Err error}`、`maskerr.New(code Code, msg string) *Error`、`maskerr.Errorf(code Code, format string, args ...any) *Error`;`repotool.Root() string`(向上找 `go.mod` 的绝对路径,测试内缓存)。

- [ ] **Step 1: 写失败测试** `maskerr/errors_test.go`:

```go
package maskerr

import (
	"errors"
	"strings"
	"testing"
)

func TestCodeValuesMatchJava(t *testing.T) {
	// 直接引用全部 14 个常量:值与 Java Code 枚举逐字一致(编译期保证存在,断言防手误改名)。
	pairs := map[Code]string{
		ConfigError:                   "CONFIG_ERROR",
		ParseError:                    "PARSE_ERROR",
		ValidationError:               "VALIDATION_ERROR",
		UnsupportedStatement:          "UNSUPPORTED_STATEMENT",
		LineageUnknown:                "LINEAGE_UNKNOWN",
		RewriteError:                  "REWRITE_ERROR",
		IOError:                       "IO_ERROR",
		PolicyServiceUnavailable:      "POLICY_SERVICE_UNAVAILABLE",
		PolicyInstanceNotFound:        "POLICY_INSTANCE_NOT_FOUND",
		IntrospectError:               "INTROSPECT_ERROR",
		MetadataInstanceNotFound:      "METADATA_INSTANCE_NOT_FOUND",
		MetadataInstanceExists:        "METADATA_INSTANCE_EXISTS",
		MetadataCredentialUnavailable: "METADATA_CREDENTIAL_UNAVAILABLE",
		MetadataServiceUnavailable:    "METADATA_SERVICE_UNAVAILABLE",
	}
	for code, want := range pairs {
		if code != Code(want) {
			t.Fatalf("code %q != %q", code, want)
		}
	}
}

func TestErrorMessageFormat(t *testing.T) {
	err := Errorf(ParseError, "statement %d: parse error (%s): boom", 1, "postgresql")
	if err.Code != ParseError || !strings.Contains(err.Error(), "statement 1: parse error (postgresql): boom") {
		t.Fatalf("unexpected: %v", err)
	}
	if !errors.Is(err, err.Err) && err.Err != nil {
		t.Fatalf("wrap lost")
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd /c/Users/yhh/orca/mask && go test ./maskerr/`
Expected: FAIL(包不存在)

- [ ] **Step 3: 最小实现** `go.mod`(`module io.sqlmask/go` + `go 1.27`)、`maskerr/errors.go`(14 个 `Code` 常量 + `Error` 结构 + `Error() string` 返回 `"CODE: message"` 风格、`New`/`Errorf`/`Unwrap`)、`cmd/sqlmask/main.go`(仅 `--version` 打印 `sql-mask-go 0.1.0`,参数缺失时打印用法;M4 才接 CLI 全集)、`internal/repotool/root.go`(`os.Executable`/`runtime.Caller(0)` 向上找 `go.mod`,找到返回目录;找不到 panic)。

- [ ] **Step 4: 运行确认通过**

Run: `go build ./... && go test ./...`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add go.mod cmd maskerr internal
git commit -m "feat: go module 骨架与 maskerr 错误码包(对齐 Java Code 枚举)"
```

---

### Task 2: dialect 包(五方言 Profile)

**Files:**
- Create: `dialect/dialect.go`、`dialect/profiles.go`
- Test: `dialect/dialect_test.go`

**Interfaces:**
- Consumes: `maskerr.Errorf`
- Produces:
```go
type Quoting int    // DoubleQuote, BackTick
type Casing int     // ToLower, Unchanged
type SchemaPathStyle int // CatalogSchema, CatalogSchemaAndSchema
type Profile struct {
	Name                         string
	Quoting                      Quoting
	UnquotedCasing, QuotedCasing Casing
	CaseSensitive                bool
	AllowTopN                    bool // 五方言均 false
	AllowInsertOverwrite         bool // hive/sparksql true
	SchemaPathStyle              SchemaPathStyle
	CanWrapDuplicateOutputNames        bool // 均 false
	SupportsDerivedColumnAliasList     bool // pg/trino/mysql true;hive/sparksql false
}
func All() []*Profile                      // 注册顺序 postgresql, trino, mysql, hive, sparksql
func ByName(name string) (*Profile, error) // 大小写不敏感;未知名 → maskerr.CONFIG_ERROR,
// message: `unsupported dialect 'x'; supported dialects: postgresql, trino, mysql, hive, sparksql`
```

- [ ] **Step 1: 写失败测试** `dialect/dialect_test.go`:表驱动断言五个方言的每个字段值,逐字对齐 Java 源(`PostgresqlDialectAdapter` 等:pg=DoubleQuote/ToLower/Unchanged/true/DEFAULT/false/false/CatalogSchema/(false,true);trino 同 pg 但 capabilities 同;mysql=BackTick/Unchanged/Unchanged/false/CatalogSchemaAndSchema/(false,true);hive、sparksql=BackTick/ToLower/Unchanged/false/CatalogSchemaAndSchema/AllowInsertOverwrite=true/(false,false));`ByName("PostgreSQL")` 命中 pg;`ByName("spark")` 返回 CONFIG_ERROR 且 message 与上面模板一致(含完整五名清单);`All()` 顺序断言。

- [ ] **Step 2: 运行确认失败**:`go test ./dialect/` → FAIL

- [ ] **Step 3: 实现** `dialect/dialect.go` + `profiles.go`(纯数据 + ByName;错误用 `maskerr.Errorf(maskerr.ConfigError, ...)`)。

- [ ] **Step 4: 运行确认通过**:`go test ./...` PASS

- [ ] **Step 5: Commit** `git commit -m "feat(dialect): 五方言 Profile 与注册表(对齐 DialectProfiles/DialectCapabilities)"`

---

### Task 3: split 包(语句拆分器,逐语义移植 SqlStatementSplitter)

**Files:**
- Create: `split/split.go`
- Test: `split/split_test.go`

**Interfaces:**
- Consumes: 无(纯函数)
- Produces: `func Statements(sql string) []string` — 语义(逐条移植 Java javadoc):顶层 `;` 切分;单引号串(含 `''` 双写转义、`E'...'` 反斜杠转义)、双引号标识符、`$$...$$`/`$tag$...$tag$` dollar 引用、`--` 行注释、**可嵌套** `/* */` 块注释内的分号不产生切分点;空白/纯注释片段丢弃;保持顺序;结果不含尾分号,两端 trim。空输入返回空切片。

- [ ] **Step 1: 写失败测试**(用例表,至少覆盖):空串/纯空白;`a;b`;行注释内的分号;嵌套块注释 `/* /* ; */ */`;`'it''s;ok'`;`E'a\';b'`;`$$;$$` 与 `$tag$;$tag$`;`"semi;colon"`;CRLF 输入(`tpcds_crlf` 形态:两条一行)顺序与去分号;尾随 `-- note` 被丢弃(Java `containsCode` 语义);多语句 trim。另加一个端到端用例:读 `mask-engine/tpcds/queries/tpcds_crlf.sql`(经 `repotool.Root()`)断言恰好 2 条。

- [ ] **Step 2: 运行确认失败** → FAIL

- [ ] **Step 3: 实现**(游标状态机,直接对译 Java `SqlStatementSplitter`;注释与源码结构可不同,语义必须逐条一致)。

- [ ] **Step 4: 运行确认通过** → PASS

- [ ] **Step 5: Commit** `git commit -m "feat(split): 顶层分号语句拆分器(移植 SqlStatementSplitter 语义)"`

---

### Task 4: 词法器 I — token 模型、词汇表机械提取、基础 token

**Files:**
- Create: `cmd/tokenextract/main.go`(一次性工具,保留在仓库)
- Create: `testdata/tokens.json`(生成物,提交)
- Create: `lexer/token.go`、`lexer/lexer.go`
- Test: `lexer/lexer_test.go`

**Interfaces:**
- Consumes: `dialect.Profile`、`repotool.Root()`
- Produces:
```go
type Pos struct{ Line, Column int } // 1 起,Tab 记 1 列(Calcite 语义)
type TokenKind int                  // Ident, QuotedIdent, String, Number, Op, Param, EOF
type Token struct {
	Kind TokenKind
	Text string   // 源文片段(引号串含引号);Ident 为**未折算**原文
	Pos  Pos
}
func Lex(p *dialect.Profile, src string) ([]Token, error) // 错误 = maskerr.PARSE_ERROR,
// message 含 `Lexical error at Line N, Column M`(与 Calcite 词法错误前缀一致,文案可微调)
```
- `testdata/tokens.json`:`{"keywords": ["SELECT","FROM",...], "operators": ["+","-","*","/","%","=","<>","!=","<","<=",">",">=","(",")",",",".",";","||","::","=>","?"]}` — 从 `mask-sqlparser/target/generated-sources/javacc/io/sqlmask/parser/SqlMaskParserImplConstants.java` 的 `tokenImage` 正则提取(提取规则:`"<X>"` 中 X 为全大写字母/下划线的词归 keywords;标点符号串按上表白名单归 operators;其余忽略)。若 target 目录缺失,先跑 `mvn -pl mask-sqlparser -am generate-sources -q` 再提取(计划执行者用 mvn 一次即可)。

- [ ] **Step 1: 写 tokenextract(先跑通工具,产出 tokens.json)**
- [ ] **Step 2: 写失败测试** `lexer/lexer_test.go`:(a) 逐 keyword 词例:`select from` 未引号 Ident、词法不区分关键字(关键字判定在 parser);(b) 引号按方言:pg 下 `"a b"`→QuotedIdent,mysql 下 `"` 开头→词法错;mysql 下 `` `a b` ``→QuotedIdent;(c) 字符串 `'a''b'`→String(整段),`E'a\'b'`;(d) 数字 `1 1.5 1e10 1.2E-3` 均为 Number;裸 `.5`(无整数位)→ 词法错(记入决策 8 风险清单,差分复核);(e) 运算符逐个 `<=`/`<>`/`!=`/`||`/`::`/`=>`;`?` 与 `?1`→Param;(f) 注释(`--`、嵌套 `/* /* */ */`)不产 token;(g) 位置:`SELECT\n  a` 中 a 的 Pos={2,3};(h) 对 `testdata/tokens.json` 每个 operator 做 round-trip 断言。
- [ ] **Step 3: 运行确认失败** → FAIL
- [ ] **Step 4: 实现 lexer**(手写扫描器:按方言取 quote 字符;`--`/嵌套块注释;字符串 `''` 转义 + `E'` 前缀(仅紧邻无空格);数字 `D+ [. D+] [E[+-]D+]`;运算符最长匹配表来自 tokens.json 的 operators(程序内常量,不是运行时读文件);Param `?`/`?D+`)
- [ ] **Step 5: 运行确认通过** → PASS
- [ ] **Step 6: Commit** `git commit -m "feat(lexer): 方言感知词法器与 JavaCC 词汇表机械提取"`

---

### Task 5: AST 数据类型(全量枚举,纯数据)

**Files:**
- Create: `ast/ast.go`、`ast/expr.go`、`ast/type.go`
- Test: `ast/ast_test.go`(仅编译性 + Pos 方法测试)

**Interfaces:**
- Consumes: 无
- Produces(后续 parser/engine 全以此为准;每个节点实现 `Pos() Pos` 与私有标记方法):

```go
// 语句
type Statement interface{ Node; isStmt() }
type Query interface{ Node; isQuery() } // Select|Values|SetOp|With|OrderBy
type Select struct { Pos Pos; Distinct bool; Items []SelectItem; From []TableRef
	Where Expr; GroupBy []Expr; Having Expr; Window []WindowDef }
type SelectItem struct { Expr Expr; Alias *Identifier; Star bool /*SELECT */
	StarQualifier []IdentPart /*t.* 时的 t;Star=false 时必须为空*/ }
type Values struct { Pos Pos; Rows [][]Expr }
type SetOp struct { Pos Pos; Op SetOpKind /*Union|Intersect|Except*/; All bool; Left, Right Query }
type With struct { Pos Pos; Recursive bool; Items []WithItem; Body Query }
type WithItem struct { Name Identifier; Columns []Identifier; Body Query }
type OrderBy struct { Pos Pos; Query Query; Items []OrderItem; Limit, Offset, Fetch Expr }
type OrderItem struct { Expr Expr; Dir OrderDir /*Asc|Desc|Unspecified*/; NullsFirst *bool /*nil=未指定*/ }
type OrderDir int
const ( Unspecified OrderDir = iota; Asc; Desc )
type Insert struct { Pos Pos; Target TableNameRef; Columns []Identifier; Source Query }
type InsertOverwrite struct { Pos Pos; Target TableNameRef; Columns []Identifier; Source Query }
type CreateTable struct { Pos Pos; Variant CreateTableVariant /*Plain|Replace|Volatile|Set|Multiset*/
	Name TableNameRef; Columns []Identifier /*可选列名清单*/; Query Query }

// 表引用
type TableRef interface{ Node; isTableRef() }
type TableNameRef struct { Pos Pos; Parts []Identifier; Alias *TableAlias }
type DerivedTable struct { Pos Pos; Query Query; Alias *TableAlias }
type Join struct { Pos Pos; Natural bool; Kind JoinKind /*Inner|Left|Right|Full|Cross|Comma*/
	Left, Right TableRef; On Expr; Using []Identifier }
type TableAlias struct { Name Identifier; Columns []Identifier }

// 表达式(Expr 接口 + 以下节点)
type Expr interface{ Node; isExpr() }
type Identifier struct { Pos Pos; Parts []IdentPart }
type IdentPart struct { Value string; Quoted bool }
type Literal struct { Pos Pos; Kind LiteralKind /*Null|True|False|Int|Decimal|Approx|String|Date|Time|Timestamp|Interval*/
	Text string; Interval IntervalLit }
type IntervalLit struct { Text string; StartUnit, EndUnit string }
type Param struct { Pos Pos; Index int; Name string } // ? → Index=-1
type Unary struct { Pos Pos; Op UnaryOp /*Neg|Plus|Not*/; Operand Expr }
type Binary struct { Pos Pos; Op BinaryOp; Left, Right Expr } // BinaryOp 枚举:
// Or And Eq Ne Lt Le Gt Ge Add Sub Concat Mul Div Mod
type IsPred struct { Pos Pos; Operand Expr; Negated bool; What IsWhat /*Null|True|False|Unknown|DistinctFrom*/; Right Expr /*DistinctFrom 时*/ }
type Between struct { Pos Pos; Operand, Low, High Expr; Negated, Symmetric bool }
type InPred struct { Pos Pos; Operand Expr; Negated bool; List []Expr; Subquery *Subquery }
type LikePred struct { Pos Pos; Operand, Pattern Expr; Negated bool; Kind LikeKind /*Like|ILike|Similar*/; Escape Expr }
type Exists struct { Pos Pos; Subquery *Subquery }
type Subquery struct { Pos Pos; Query Query }
type FunctionCall struct { Pos Pos; Name Identifier; Distinct bool; Star bool; Args []Expr; Over *WindowSpec }
type Case struct { Pos Pos; Operand Expr; Whens []When; Else Expr }
type When struct { Cond, Then Expr }
type Cast struct { Pos Pos; Operand Expr; Type TypeSpec }
type Collate struct { Pos Pos; Operand Expr; Collation Identifier }

type WindowSpec struct { Pos Pos; PartitionBy []Expr; Order []OrderItem; Frame *Frame }
type Frame struct { Unit FrameUnit /*Rows|Range*/; Start, End FrameBound }
type FrameBound struct { Kind BoundKind /*UnboundedPreceding|Preceding|CurrentRow|Following|UnboundedFollowing*/; Offset Expr }
type TypeSpec struct { Pos Pos; Name string /*规范化名,如 VARCHAR、DOUBLE PRECISION*/; Precision, Scale Expr }
```

- [ ] **Step 1: 写失败测试**(包能否编译 + `Pos()` 返回字段值)
- [ ] **Step 2: 确认失败 → Step 3: 全量类型实现 → Step 4: 确认通过**
- [ ] **Step 5: Commit** `git commit -m "feat(ast): SQL AST 全量节点类型(M1 语法面)"`

---

### Task 6: parser 骨架 + 主表达式链

**Files:**
- Create: `parser/parser.go`、`parser/expr.go`
- Test: `parser/expr_test.go`、`parser/testutil_test.go`

**Interfaces:**
- Consumes: `lexer.Lex`、`dialect.Profile`、`ast.*`
- Produces:
```go
func New(p *dialect.Profile, src string) *Parser
func (p *Parser) ParseStatement() (ast.Statement, error) // Task 8 起接语句层;本任务先提供 ParseExpr
func (p *Parser) ParseExpr() (ast.Expr, error)           // 导出供测试与 M2 rowfilter 复用
// 关键字判定:func (p *Parser) atKw(word string) bool  // 未引号 Ident 且 strings.EqualFold
// 标识符折算:func (p *Parser) foldIdent(raw string, quoted bool) string // 按 Profile casing
// 测试助手 testutil_test.go:normAST(将 Pos 清零后 reflect.DeepEqual 比对)
```
- 表达式实现顺序(每小步一测):primary(字面量/标识符/`(` expr `)`/函数调用/CASE/CAST/EXISTS/标量子查询/Param)→ 一元 → 乘除模 → 加减与 `||` → 谓词层(BETWEEN/IN/LIKE/IS/IN 子查询,可链式:如 `a NOT BETWEEN b AND c AND d` 归约顺序)→ NOT → AND → OR。

- [ ] **Step 1: 失败测试**:字面量五类;多段名 `a.b.c` 与 `"A"."b"`(casing 断言:pg 折小写、mysql 原样、引号内不折);`COUNT(*)`、`COUNT(DISTINCT x)`、`f(a, b)`;`CASE WHEN..THEN..ELSE..END` 与简单 CASE;`CAST(x AS DECIMAL(10,2))`;`EXISTS (SELECT 1)`;`(SELECT 1)` 标量子查询;`?`/`?1`;一元 `-x +y`;`a + b * c`/`a || b`/`a = b AND c`/`NOT a OR b`(结构断言:Binary 树形);`a BETWEEN x AND y`、`a NOT IN (1,2)`、`a LIKE 'x' ESCAPE '!'`、`a IS NOT NULL`、`a IS DISTINCT FROM b`;`f(x) OVER (PARTITION BY a ORDER BY b ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)`。每个用例给出期望 AST 的字面构造(测试代码完整写出,不许 `...`)。样例(定下全部测试的书写范式,`normAST` 见 testutil):

```go
func TestPrecedence(t *testing.T) {
	got, err := newTestParser(t, "postgresql").ParseExprStr(`a + b * c`)
	if err != nil { t.Fatal(err) }
	want := &ast.Binary{Op: ast.Add,
		Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
		Right: &ast.Binary{Op: ast.Mul,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
}
```
- [ ] **Step 2: 确认失败 → Step 3: 实现上述优先级链与 primary(严格按 M1 决策 5/7/8 的支持面;不支持的构造报 `maskerr.PARSE_ERROR`,message 含 `Line N, Column M`)→ Step 4: 确认通过**
- [ ] **Step 5: Commit** `git commit -m "feat(parser): 表达式优先级链与 primary 全量(M1 语法面)"`

---

### Task 7: FROM 子句与 SELECT 核心

**Files:**
- Create: `parser/select.go`
- Test: `parser/select_test.go`

**Interfaces:**
- Consumes: Task 6 全部
- Produces: `func (p *Parser) ParseQuery() (ast.Query, error)`(本任务覆盖裸 SELECT;后续任务扩展其他 Query)、`parseFrom`/`parseSelectItem`(内部)。
- 语法点:SELECT [DISTINCT] items;item = `*` | expr [[AS] alias];FROM 表引用链(逗号 = Comma Join);表引用:TableName(1–4 段)[[AS] alias [(cols)]] | `(` Query `)` [[AS] alias [(cols)]](派生表);JOIN 系列:`[INNER|LEFT [OUTER]|RIGHT [OUTER]|FULL [OUTER]|CROSS] JOIN` + `ON expr | USING (c1, c2)`、`NATURAL [LEFT|RIGHT|... ] JOIN`、逗号连接;WHERE/GROUP BY/HAVING。

- [ ] **Step 1: 失败测试**(每条给完整期望 AST):`SELECT * FROM t`、`SELECT t.* FROM t`(StarQualifier=["t"])、`SELECT 1`(无 FROM);`SELECT a x, b AS y`;`FROM a, b, c` → 嵌套 Comma Join;`a JOIN b ON e`;`a LEFT OUTER JOIN b USING (x)`;`a CROSS JOIN b`;`NATURAL JOIN`;派生表 `FROM (SELECT x FROM t) AS d (dx)`;WHERE/GROUP/HAVING 组合;`SELECT DISTINCT a, b`。
- [ ] **Step 2–4: 红→绿(可按语法点分多轮红绿循环)**
- [ ] **Step 5: Commit** `git commit -m "feat(parser): SELECT 核心与 FROM 全覆盖(M1 语法面)"`

---

### Task 8: 集合运算、ORDER BY/LIMIT/OFFSET/FETCH、TOP(n) 拒绝

**Files:**
- Modify: `parser/select.go`
- Test: `parser/setop_test.go`

**Interfaces:**
- Consumes/Produces: `ParseQuery` 扩展;新增内部 `parseOrderAndTail`(statement 级 ORDER BY/LIMIT/OFFSET/FETCH → `ast.OrderBy` 包装)。
- 语法点:`UNION [ALL|DISTINCT]`、`INTERSECT`、`EXCEPT`(优先级 INTERSECT > UNION/EXCEPT,同级左结合);statement 级 `ORDER BY e [ASC|DESC] [NULLS FIRST|LAST]` 与 `LIMIT n|ALL`、`OFFSET n [ROW|ROWS]`、`FETCH FIRST|NEXT n [ROW|ROWS] ONLY`(`LIMIT n OFFSET m` 合法;`OFFSET n FETCH..` 合法;`LIMIT n FETCH..` → PARSE_ERROR——Calcite 语法中 LIMIT 与 FETCH 互斥);`TOP (n)` → `PARSE_ERROR`(五方言均关,见决策 4;message 提及 TOP)。括号查询 `(SELECT ...) UNION (SELECT ...)`(操作数为 ParenQuery → 解析为括号内的 Query 直接挂上)。

- [ ] **Step 1: 失败测试**:`a UNION ALL b UNION c`(左结合树)、`a INTERSECT b UNION c`(先 intersect)、`ORDER BY 1 DESC NULLS LAST`、`LIMIT 5`、`LIMIT ALL OFFSET 3 ROWS`、`FETCH FIRST 10 ROWS ONLY`、`SELECT .. LIMIT 1 FETCH FIRST 1 ROWS ONLY` → PARSE_ERROR、`SELECT TOP (5) ..` → PARSE_ERROR(五方言逐个断言)、`(SELECT 1) UNION (SELECT 2)`、ORDER BY 包装节点类型断言(`OrderBy{Query: *Select}`)。
- [ ] **Step 2–4: 红→绿**
- [ ] **Step 5: Commit** `git commit -m "feat(parser): 集合运算与排序/限尾子句,TOP(n) 按方言拒绝"`

---

### Task 9: WITH/CTE、INSERT、CREATE TABLE AS、INSERT OVERWRITE(语句层齐)

**Files:**
- Create: `parser/stmt.go`、`engine/classify.go`
- Test: `parser/stmt_test.go`、`engine/classify_test.go`

**Interfaces:**
- Consumes: `ParseQuery`、`ast.*`、`maskerr`
- Produces:
```go
func (p *Parser) ParseStatement() (ast.Statement, error) // 完整入口:分发 SELECT/WITH/INSERT/
// CREATE/INSERT OVERWRITE/其余关键字 → PARSE_ERROR(不认识的语句开头)
func Classify(stmt ast.Statement, ordinal int) error // engine 包,镜像 Java classify:
// Insert/InsertOverwrite → nil;Select/Values/SetOp(裸 VALUES 顶层与 SetOp 顶层——见下)→ 按下述规则;
// With → Body 为 Query 则 nil;CreateTable → Query==nil 时 UNSUPPORTED,Variant!=Plain 时 UNSUPPORTED
// (message: `statement N: unsupported statement kind <K>; only SELECT and WITH ... SELECT queries are supported in this version`,
//  变体时 `<K>` 用 `CREATE_TABLE`;变体拒绝 message 另述 variant 名)
```
- **顶层 VALUES/SetOp 的 classify 对齐**(镜像 SqlOrderBy 包装行为):裸 `VALUES ...`(无 ORDER BY/LIMIT)在 Java 解析为 SqlValues → classify default → UNSUPPORTED_STATEMENT;`VALUES ... ORDER BY/LIMIT` → SqlOrderBy 包 SqlValues → 同样 UNSUPPORTED(Java isQuery(VALUES)=false)。因此 Go:`Classify` 对顶层 `Values` 与 `SetOp` 一律 UNSUPPORTED_STATEMENT;`(VALUES ..)` 出现在 FROM/INSERT 源中不受影响。**INSERT 源**为 Query(Select/Values/SetOp/OrderBy 包装均可,kind 均为 INSERT → nil)。
- 语法点:WITH [RECURSIVE] name [(cols)] AS (query), ... body(决策 2:RECURSIVE 仅解析);INSERT INTO target [(cols)] query;CREATE TABLE [REPLACE|VOLATILE|SET|MULTISET] name [(col,..)] AS query(变体词记录);INSERT OVERWRITE [TABLE] target [(cols)] query——`AllowInsertOverwrite=false` 的方言 → PARSE_ERROR;出现 `PARTITION`/`DIRECTORY` → PARSE_ERROR。

- [ ] **Step 1: 失败测试**:CTE 一条 + 多 CTE + 列改名;`WITH RECURSIVE` 解析成功且 Recursive=true;`INSERT INTO t (a,b) SELECT ..`;`INSERT INTO t VALUES (1,2),(3,4)`;`CREATE TABLE x AS SELECT ..`(Plain);`CREATE TABLE REPLACE x AS SELECT ..` 解析成功 + Classify 报 UNSUPPORTED_STATEMENT;`CREATE TABLE x`(无 AS)→ Classify UNSUPPORTED;`INSERT OVERWRITE TABLE t SELECT ..` hive/sparksql 解析成功、pg/mysql/trino PARSE_ERROR;`INSERT OVERWRITE t PARTITION (dt) SELECT ..` → PARSE_ERROR;`UPDATE t SET ..` → PARSE_ERROR(`UPDATE` 认识但不支持,报 PARSE_ERROR?——**决策**:未实现语句的首词若在 JavaCC keywords 表(Task 4 tokens.json)内 → PARSE_ERROR(message 含首词),镜像"Java 语法认识但 mask 不放行/或语法不支持"在 M1 差分里的可观测等价:差分护栏会实测这些首词,不一致项记入契约);裸 `SELECT 1`(顶层无 ORDER/LIMIT)→ `*ast.Select` 且 Classify nil;裸 `VALUES (1)` → Classify UNSUPPORTED_STATEMENT。
- [ ] **Step 2–4: 红→绿**
- [ ] **Step 5: Commit** `git commit -m "feat(parser,engine): WITH/INSERT/CTAS/INSERT OVERWRITE 语句层与 classify 移植"`

---

### Task 10: 契约 harness — Java CLI runner 与语料差分

**Files:**
- Create: `contract/java.go`、`contract/corpus.go`、`contract/verdict.go`
- Create: `cmd/contractgen/main.go`
- Create: `testdata/contract/parse-verdicts.json`(生成物,提交)
- Test: `contract/diff_test.go`

**Interfaces:**
- Consumes: `split`、`parser`、`engine.Classify`、`repotool.Root()`
- Produces:
```go
type Verdict struct { Stage string; Code string } // Stage: "parse"|"classify"|"accept"(M1 解析期)
func GoVerdict(sql, dialectName string) Verdict   // split→Lex/Parse→Classify;panic 兜底转 parse 拒绝
type JavaRun struct { Exit int; Stdout, Stderr string }
func RunJava(dialectName, metadataPath, sql string) (JavaRun, error) // exec java -jar <repo>/mask-core/target/sql-mask.jar
func VerdictFromJava(r JavaRun) Verdict // 0→accept;[PARSE_ERROR] statement N: parse error→parse;
// [UNSUPPORTED_STATEMENT] statement N: unsupported→classify;其余→"later"(M1 等价于 accept)
func LoadCorpus() []Case // 扫 mask-engine/tpcds/queries/*.sql 与 mask-engine/src/test/resources/golden/write-statements.sql:
// 方言映射按文件名:含 mysql→mysql、含 trino→trino、其余→postgresql;按 split.Statements 切;
// 全部文件全部语句进契约,不做任何特殊化(不支持的语句正是契约的一部分)
func Key(c Case) string // file+"#"+ordinal+"@"+dialect,contractgen 与 diff_test 共用
func Compare(corpus []Case, java map[string]Verdict) []Mismatch
func Compare(corpus []Case, java map[string]Verdict) []Mismatch // key = file+"#"+ordinal+"@"+dialect
```
- `cmd/contractgen/main.go`:对全语料逐条调 `RunJava`(失败语句 exit=1 属正常数据),写 `testdata/contract/parse-verdicts.json`(`{key, dialect, sql, verdict}` 数组)。运行约几十秒,结果提交进仓库。
- `contract/diff_test.go`:两段测试。(a) `TestContractAgainstRecorded`:读提交的 parse-verdicts.json,对每条跑 `GoVerdict` 比对(M1 绿判定;`java` 或 jar 不存在时仍可运行,因为比对对象是记录文件)。(b) `TestContractAgainstLiveJava`:存在 java+jar 时跳转实时比对,t.Skip 否则。

- [ ] **Step 1: 失败测试**(先写 VerdictFromJava 的纯函数测试:构造 stderr 样例断言分类)
- [ ] **Step 2–4: 红→绿**(实现 runner/corpus/verdict;`exec.Command` 不经 shell,注意给 java 传 Windows 绝对路径由 repotool 拼出)
- [ ] **Step 3.5: 生成契约**:`go run ./cmd/contractgen` → 提交 parse-verdicts.json
- [ ] **Step 5: Commit** `git commit -m "feat(contract): Java CLI 差分 harness 与解析期契约文件"`

---

### Task 11: 语料差分全绿(M1 验收)与差分修复

**Files:**
- Modify: 前述 parser/lexer 文件(按差分结果修)
- Test: `contract/diff_test.go`(此时必须全绿)

**Interfaces:**
- Consumes: Task 10 全部
- Produces: `go test ./...` 全绿,含契约差分;`docs/superpowers/plans/m1-differential-notes.md` 记录每处修正。

- [ ] **Step 1: 运行 `go test ./contract/ -run TestContractAgainstRecorded -v` 收集全部 Mismatch**
- [ ] **Step 2: 对每个 Mismatch 定位**:解析接受差异 → 查 `mask-sqlparser` 语法(`src/main/codegen/templates/Parser.jj` 与 fmpp includes);classify 差异 → 对译 `AbstractCalciteDialectAdapter.classify`。按 M1 决策 4 的协议把关键字/语法缺口落成"修复 + 回归用例"(每个差异必须在 parser 测试里留一个最小复现,不许只修不测)。
- [ ] **Step 3: 重复至 Mismatch 清单为空;`go build ./... && go vet ./... && go test ./...` 全绿**
- [ ] **Step 4: 写 m1-differential-notes.md**(每项:语料 key、Java 行为、Go 初始行为、根因、修复)
- [ ] **Step 5: Commit** `git commit -m "fix(parser): 语料差分清零,M1 解析期契约全绿(附 notes)"`

---

### Task 12: hive/sparksql 小语料与方言差异回归

**Files:**
- Create: `testdata/extra/hive.sql`、`testdata/extra/sparksql.sql`、`testdata/extra/mysql.sql`、`testdata/extra/trino.sql`
- Modify: `contract/corpus.go`(LoadCorpus 追加 testdata/extra/ 下语料:按文件名前缀映射方言 hive→hive、sparksql→sparksql、mysql→mysql、trino→trino)
- Test: `dialect/dialect_test.go`(扩充)、`contract/diff_test.go`

**Interfaces:**
- Produces: extra 语料进入契约(重跑 contractgen 更新 parse-verdicts.json)。

- [ ] **Step 1: 写四个方言小语料**(每文件 6–10 条,注释标注期望,内容:反引号 vs 双引号标识符、INSERT OVERWRITE 接受/拒绝、大小写折算敏感用例如 `SELECT Foo FROM Bar`、2 段名形态、TOP 拒绝、RECURSIVE 解析接受)
- [ ] **Step 2: 重跑 `go run ./cmd/contractgen`**(更新契约文件;Java 端 hive/spark 语句若触发后端阶段失败,M1 视为解析接受,由 VerdictFromJava 的 "later" 分类覆盖)
- [ ] **Step 3: `go test ./...` 全绿(契约含 extra 语料)**
- [ ] **Step 4: Commit** `git commit -m "test(contract): hive/sparksql/mysql/trino 方言差异语料进入解析期契约"`

---

### Task 13: M1 收口 — 版本横切测试与文档

**Files:**
- Create: `parser/roundtrip_smoke_test.go`、`README-go.md`(M1 说明)
- Modify: `cmd/sqlmask/main.go`(--version 已有;增加 `--parse <file> --dialect d` 调试入口:逐语句解析,输出 `ok` / `PARSE_ERROR: ...`,供人工验证)

**Interfaces:**
- Produces: `cmd/sqlmask --parse` 调试入口;M1 完成记录。

- [ ] **Step 1: 失败测试**:对全部语料做"解析成功的语句 → 断言顶层语句类型分布"(SELECT/With/Insert/CreateTable/InsertOverwrite 计数快照写死在测试里,防 AST 结构回归);`--parse` 入口的 exec 冒烟测试。
- [ ] **Step 2–4: 红→绿**
- [ ] **Step 5: README-go.md**:M1 范围、包结构、差分护栏用法(contractgen/diff_test)、已知风险清单(本计划 M1 决策 5 的清单 + Task 11 notes)。
- [ ] **Step 6: Commit** `git commit -m "docs: M1 收口——调试入口、结构快照测试与 README-go"`

---

## Spec 覆盖对照(M1 范围内)

- spec §2 M1 行(解析器+AST+方言开关+差分)→ Task 1–13。
- spec §3(手写解析器、方言开关、TOP/INSERT OVERWRITE、LOOKAHEAD 歧义边界)→ 决策 1/4/10、Task 4–9、Task 11 差分兜底。
- spec §4 AST(节点集合、位置、unparse 除外——unparse 属 M3)→ Task 5。
- spec §10 差分护栏(契约文件、接受/拒绝边界)→ Task 10–12。
- spec §5–§9(校验/血缘/行过滤/配置/CLI/HTTP)→ **不在本计划**,M2–M5 计划承接(M1 的 `ParseExpr` 导出即为 rowfilter 白名单预检预留的接口)。

## 风险与回退

- `mask-sqlparser/target` 生成源缺失 → Task 4 先跑 `mvn -pl mask-sqlparser -am generate-sources`(本机 mvn 可用性在执行时验证;不可用则用 `git show HEAD:...` 无法替代——生成物不入库,需本地 Maven)。
- Java 差分运行慢(每条一次 JVM 启动,~1s/条)→ contractgen 一次性生成 + 提交记录文件;实时比对测试可选。
- 差分清零发现语法面缺口(风险清单命中)→ 按决策 4 协议补;若命中面过大(>15 处),停下来回到 spec 修订 §3 决策再继续(升级路径)。
