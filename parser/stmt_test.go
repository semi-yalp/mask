package parser

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/internal/repotool"
	"io.sqlmask/go/lexer"
	"io.sqlmask/go/maskerr"
)

// ---------------------------------------------------------------------------
// 测试助手(与 testutil_test.go 的表达式层助手同族)
// ---------------------------------------------------------------------------

// ParseStmtStr 对 src 重新做词法分析后解析为顶层语句,供测试书写
// newTestParser(t, "postgresql").ParseStmtStr(`...`) 形态的用例。
func (p *Parser) ParseStmtStr(src string) (ast.Statement, error) {
	toks, err := lexer.Lex(p.profile, src)
	if err != nil {
		return nil, err
	}
	p.toks, p.cur, p.lexErr = toks, 0, nil
	return p.ParseStatement()
}

// mustStmt 解析成功则返回语句,失败则令测试致命退出。
func mustStmt(t *testing.T, dialectName, src string) ast.Statement {
	t.Helper()
	got, err := newTestParser(t, dialectName).ParseStmtStr(src)
	if err != nil {
		t.Fatalf("ParseStmtStr(%q) with dialect %s: unexpected error: %v", src, dialectName, err)
	}
	return got
}

// wantStmtError 断言 src 解析失败,错误码为 maskerr.PARSE_ERROR,message 含
// "Line 1, Column wantCol"(wantCol<=0 时只断言含 "Line"),并含 contains
// 中每个子串(通常放语句首词)。
func wantStmtError(t *testing.T, dialectName, src string, wantCol int, contains ...string) {
	t.Helper()
	_, err := newTestParser(t, dialectName).ParseStmtStr(src)
	if err == nil {
		t.Fatalf("ParseStmtStr(%q) with dialect %s: expected PARSE_ERROR, got nil error", src, dialectName)
	}
	var me *maskerr.Error
	if !errors.As(err, &me) {
		t.Fatalf("ParseStmtStr(%q): error %T is not *maskerr.Error: %v", src, err, err)
	}
	if me.Code != maskerr.ParseError {
		t.Fatalf("ParseStmtStr(%q): error code = %s, want PARSE_ERROR (message: %s)", src, me.Code, me.Message)
	}
	needle := "Line 1"
	if wantCol > 0 {
		needle = "Line 1, Column " + strconv.Itoa(wantCol)
	}
	if !strings.Contains(me.Message, needle) {
		t.Fatalf("ParseStmtStr(%q): message %q does not contain %q", src, me.Message, needle)
	}
	for _, c := range contains {
		if !strings.Contains(me.Message, c) {
			t.Fatalf("ParseStmtStr(%q): message %q does not contain %q", src, me.Message, c)
		}
	}
}

// stmtAs 类型断言助手:stmt 必须为 *T 形态,否则致命退出。
func stmtAs[T ast.Statement](t *testing.T, stmt ast.Statement) T {
	t.Helper()
	s, ok := stmt.(T)
	if !ok {
		t.Fatalf("statement type = %T, want %T", stmt, *new(T))
	}
	return s
}

// identText 取单段标识符的折算文本(TableNameRef.Parts 的元素是 Identifier)。
func identText(id ast.Identifier) string { return id.Parts[0].Value }

// ---------------------------------------------------------------------------
// Step 1 清单:语句层解析
// ---------------------------------------------------------------------------

// 裸 SELECT(顶层无 ORDER/LIMIT)→ *ast.Select。
func TestParseStatementBareSelect(t *testing.T) {
	s := stmtAs[*ast.Select](t, mustStmt(t, "postgresql", `SELECT 1`))
	if len(s.Items) != 1 {
		t.Fatalf("items = %d, want 1", len(s.Items))
	}
}

// 括号查询作顶层语句:括号透明,语句即内部查询(镜像 jar 实测 (SELECT 1) → SELECT)。
func TestParseStatementParenQuery(t *testing.T) {
	stmtAs[*ast.Select](t, mustStmt(t, "postgresql", `(SELECT 1)`))
	stmtAs[*ast.Values](t, mustStmt(t, "postgresql", `(VALUES (1))`))
}

// CTE 一条 + 多 CTE + 列改名;RECURSIVE 仅解析记录(决策 2)。
func TestParseWith(t *testing.T) {
	// 一条 CTE
	w := stmtAs[*ast.With](t, mustStmt(t, "postgresql", `WITH c AS (SELECT 1) SELECT * FROM c`))
	if len(w.Items) != 1 || w.Recursive {
		t.Fatalf("items = %d, recursive = %v, want 1/false", len(w.Items), w.Recursive)
	}
	if w.Items[0].Name.Parts[0].Value != "c" {
		t.Fatalf("CTE name = %q, want c", w.Items[0].Name.Parts[0].Value)
	}
	if w.Items[0].Body == nil {
		t.Fatal("CTE body = nil")
	}
	if _, ok := w.Body.(*ast.Select); !ok {
		t.Fatalf("with body type = %T, want *ast.Select", w.Body)
	}
	// 多 CTE
	w2 := stmtAs[*ast.With](t, mustStmt(t, "postgresql",
		`WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a, b`))
	if len(w2.Items) != 2 {
		t.Fatalf("items = %d, want 2", len(w2.Items))
	}
	// 列改名
	w3 := stmtAs[*ast.With](t, mustStmt(t, "postgresql",
		`WITH c (x, y) AS (SELECT 1, 2) SELECT * FROM c`))
	if got := len(w3.Items[0].Columns); got != 2 {
		t.Fatalf("CTE columns = %d, want 2", got)
	}
	if w3.Items[0].Columns[0].Parts[0].Value != "x" || w3.Items[0].Columns[1].Parts[0].Value != "y" {
		t.Fatalf("CTE columns = %v, want x,y", w3.Items[0].Columns)
	}
	// WITH RECURSIVE:解析成功且 Recursive=true(决策 2:仅记录)
	w4 := stmtAs[*ast.With](t, mustStmt(t, "postgresql", `WITH RECURSIVE c AS (SELECT 1) SELECT * FROM c`))
	if !w4.Recursive {
		t.Fatal("WITH RECURSIVE parsed but Recursive = false")
	}
}

// WITH 体含集合运算:WITH 包住归约后的集合运算(镜像 addWith(toTree));
// WITH + ORDER BY:OrderBy 包在最外(镜像 SqlOrderBy 包装)。
func TestParseWithBodyAndTail(t *testing.T) {
	w := stmtAs[*ast.With](t, mustStmt(t, "postgresql",
		`WITH c AS (SELECT 1) SELECT 1 UNION SELECT 2`))
	if _, ok := w.Body.(*ast.SetOp); !ok {
		t.Fatalf("with body type = %T, want *ast.SetOp", w.Body)
	}
	ob := stmtAs[*ast.OrderBy](t, mustStmt(t, "postgresql",
		`WITH c AS (SELECT 1) SELECT 1 UNION SELECT 2 ORDER BY 1`))
	if _, ok := ob.Query.(*ast.With); !ok {
		t.Fatalf("order-by query type = %T, want *ast.With", ob.Query)
	}
}

// INSERT INTO t (a,b) SELECT .. 与 INSERT INTO t VALUES (1,2),(3,4)。
func TestParseInsert(t *testing.T) {
	ins := stmtAs[*ast.Insert](t, mustStmt(t, "postgresql", `INSERT INTO t (a, b) SELECT 1, 2`))
	if got := len(ins.Target.Parts); got != 1 || identText(ins.Target.Parts[0]) != "t" {
		t.Fatalf("target = %v, want [t]", ins.Target.Parts)
	}
	if len(ins.Columns) != 2 || ins.Columns[0].Parts[0].Value != "a" || ins.Columns[1].Parts[0].Value != "b" {
		t.Fatalf("columns = %v, want a,b", ins.Columns)
	}
	if _, ok := ins.Source.(*ast.Select); !ok {
		t.Fatalf("source type = %T, want *ast.Select", ins.Source)
	}
	// VALUES 源
	ins2 := stmtAs[*ast.Insert](t, mustStmt(t, "postgresql", `INSERT INTO t VALUES (1,2),(3,4)`))
	v, ok := ins2.Source.(*ast.Values)
	if !ok {
		t.Fatalf("source type = %T, want *ast.Values", ins2.Source)
	}
	if len(v.Rows) != 2 || len(v.Rows[0]) != 2 || len(v.Rows[1]) != 2 {
		t.Fatalf("rows = %v, want 2x2", v.Rows)
	}
	// 无列清单形态
	ins3 := stmtAs[*ast.Insert](t, mustStmt(t, "postgresql", `INSERT INTO t SELECT 1`))
	if ins3.Columns != nil {
		t.Fatalf("columns = %v, want nil", ins3.Columns)
	}
}

// INSERT 源:括号查询、集合运算、限尾包装、WITH 头均可(jar 实测面)。
func TestParseInsertSourceShapes(t *testing.T) {
	for _, src := range []string{
		`INSERT INTO t (SELECT 1)`,
		`INSERT INTO t SELECT 1 UNION SELECT 2`,
		`INSERT INTO t SELECT 1 ORDER BY 1 LIMIT 2`,
		`INSERT INTO t VALUES (1,2) ORDER BY 1`,
		`INSERT INTO t WITH c AS (SELECT 1) SELECT * FROM c`,
		`INSERT INTO t (WITH c AS (SELECT 1) SELECT * FROM c)`,
	} {
		ins, err := newTestParser(t, "postgresql").ParseStmtStr(src)
		if err != nil {
			t.Fatalf("ParseStmtStr(%q): unexpected error: %v", src, err)
		}
		if _, ok := ins.(*ast.Insert); !ok {
			t.Fatalf("ParseStmtStr(%q): statement type = %T, want *ast.Insert", src, ins)
		}
	}
}

// INSERT 拒收面:INTO 必需;空列清单拒;列清单与括号源按 LOOKAHEAD 区分。
func TestParseInsertRejects(t *testing.T) {
	for _, src := range []string{
		`INSERT t SELECT 1`,              // 缺 INTO
		`INSERT INTO t () SELECT 1`,      // 空列清单
		`INSERT INTO t (a, b)`,           // 缺源
		`INSERT INTO t (a INT) SELECT 1`, // 列清单带类型(fork conformance 拒)
	} {
		wantStmtError(t, "postgresql", src, 0)
	}
}

// CREATE TABLE [REPLACE|VOLATILE|SET|MULTISET] name [(col,..)] AS query
// —— 变体词位置逐字镜像 fork 实测:CREATE [OR REPLACE] [MULTISET|SET] [VOLATILE] TABLE。
func TestParseCreateTable(t *testing.T) {
	ct := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE TABLE x AS SELECT 1`))
	if ct.Variant != ast.Plain {
		t.Fatalf("variant = %v, want Plain", ct.Variant)
	}
	if identText(ct.Name.Parts[0]) != "x" {
		t.Fatalf("name = %q, want x", identText(ct.Name.Parts[0]))
	}
	if _, ok := ct.Query.(*ast.Select); !ok {
		t.Fatalf("query type = %T, want *ast.Select", ct.Query)
	}
	// CREATE TABLE REPLACE x 不是合法语法(jar 实测 FAIL);合法 REPLACE 形态
	// 为 CREATE OR REPLACE TABLE(jar 实测 ops[0]=TRUE)。
	ct2 := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE OR REPLACE TABLE x AS SELECT 1`))
	if ct2.Variant != ast.Replace {
		t.Fatalf("variant = %v, want Replace", ct2.Variant)
	}
	ct3 := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE VOLATILE TABLE x AS SELECT 1`))
	if ct3.Variant != ast.Volatile {
		t.Fatalf("variant = %v, want Volatile", ct3.Variant)
	}
	ct4 := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE SET TABLE x AS SELECT 1`))
	if ct4.Variant != ast.Set {
		t.Fatalf("variant = %v, want Set", ct4.Variant)
	}
	ct5 := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE MULTISET TABLE x AS SELECT 1`))
	if ct5.Variant != ast.Multiset {
		t.Fatalf("variant = %v, want Multiset", ct5.Variant)
	}
	// 无 AS(纯建表)解析成功,Query 为 nil(Classify 拒)
	ct6 := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE TABLE x`))
	if ct6.Query != nil {
		t.Fatalf("query = %v, want nil", ct6.Query)
	}
	// IF NOT EXISTS 解析接受(AST 无字段,M1 记录为 watchlist)
	if _, err := newTestParser(t, "postgresql").ParseStmtStr(`CREATE TABLE IF NOT EXISTS x AS SELECT 1`); err != nil {
		t.Fatalf("CREATE TABLE IF NOT EXISTS: unexpected error: %v", err)
	}
	// 列名清单(名称形态)
	ct7 := stmtAs[*ast.CreateTable](t, mustStmt(t, "postgresql", `CREATE TABLE x (a, b) AS SELECT 1`))
	if len(ct7.Columns) != 2 {
		t.Fatalf("columns = %d, want 2", len(ct7.Columns))
	}
	// CTAS 源可为 VALUES/WITH 查询(jar 实测面)
	if _, err := newTestParser(t, "postgresql").ParseStmtStr(`CREATE TABLE x AS VALUES (1)`); err != nil {
		t.Fatalf("CREATE TABLE x AS VALUES: unexpected error: %v", err)
	}
	if _, err := newTestParser(t, "postgresql").ParseStmtStr(`CREATE TABLE x AS WITH c AS (SELECT 1) SELECT * FROM c`); err != nil {
		t.Fatalf("CREATE TABLE x AS WITH: unexpected error: %v", err)
	}
	// 变体词错位/非法形态拒(jar 实测 FAIL 面)
	for _, src := range []string{
		`CREATE TABLE REPLACE x AS SELECT 1`,                 // REPLACE 不能在 TABLE 后
		`CREATE REPLACE TABLE x AS SELECT 1`,                 // REPLACE 必须带 OR
		`CREATE VOLATILE MULTISET TABLE x AS SELECT 1`,       // MULTISET/SET 必须在 VOLATILE 前
		`CREATE OR REPLACE VOLATILE SET TABLE x AS SELECT 1`, // SET 必须在 VOLATILE 前
		`CREATE TABLE SET x AS SELECT 1`,
		`CREATE VIEW v AS SELECT 1`, // 仅 CREATE TABLE 在产生式内
		`CREATE x AS SELECT 1`,
	} {
		wantStmtError(t, "postgresql", src, 0)
	}
}

// INSERT OVERWRITE [TABLE] .. SELECT:hive/sparksql 解析成功,pg/mysql/trino
// PARSE_ERROR(AllowInsertOverwrite=false 方言)。
func TestParseInsertOverwriteDialectGate(t *testing.T) {
	for _, d := range []string{"hive", "sparksql"} {
		ins := stmtAs[*ast.InsertOverwrite](t, mustStmt(t, d, `INSERT OVERWRITE TABLE t SELECT 1`))
		if got := identText(ins.Target.Parts[0]); got != "t" {
			t.Fatalf("target = %q, want t", got)
		}
		// 无 TABLE 形态
		stmtAs[*ast.InsertOverwrite](t, mustStmt(t, d, `INSERT OVERWRITE t SELECT 1`))
		// 带列清单
		ins2 := stmtAs[*ast.InsertOverwrite](t, mustStmt(t, d, `INSERT OVERWRITE TABLE t (a,b) SELECT 1,2`))
		if len(ins2.Columns) != 2 {
			t.Fatalf("columns = %d, want 2", len(ins2.Columns))
		}
		// VALUES 源
		stmtAs[*ast.InsertOverwrite](t, mustStmt(t, d, `INSERT OVERWRITE t VALUES (1)`))
	}
	for _, d := range []string{"postgresql", "mysql", "trino"} {
		wantStmtError(t, d, `INSERT OVERWRITE TABLE t SELECT 1`, 1, "INSERT OVERWRITE")
	}
}

// INSERT OVERWRITE 的 PARTITION / DIRECTORY 形态一律 PARSE_ERROR(简报决策 10)。
func TestParseInsertOverwritePartitionDirectory(t *testing.T) {
	wantStmtError(t, "hive", `INSERT OVERWRITE TABLE t PARTITION (dt) SELECT 1`, 0, "PARTITION")
	wantStmtError(t, "hive", `INSERT OVERWRITE t PARTITION (dt = 'a') SELECT 1`, 0, "PARTITION")
	wantStmtError(t, "sparksql", `INSERT OVERWRITE DIRECTORY '/tmp/x' SELECT 1`, 0, "DIRECTORY")
	wantStmtError(t, "hive", `INSERT OVERWRITE DIRECTORY '/tmp/x' SELECT 1`, 0, "DIRECTORY")
}

// 认识但未实现的语句首词(JavaCC keywords 表内)→ PARSE_ERROR,消息含首词与位置。
func TestParseStatementUnsupportedFirstWord(t *testing.T) {
	for _, src := range []string{
		`UPDATE t SET a = 1`,
		`DELETE FROM t WHERE a = 1`,
		`MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = 1`,
		`GRANT SELECT ON t TO u`,
		`ALTER TABLE t ADD COLUMN c INT`,
		`EXPLAIN SELECT 1`,
		`TRUNCATE TABLE t`,
		`TABLE t`,
		`SET x = 1`,
		`DESCRIBE t`,
		`CALL proc(1)`,
	} {
		word := strings.Fields(src)[0]
		wantStmtError(t, "postgresql", src, 1, word)
	}
}

// 完全不认识的 token → PARSE_ERROR;残留 token 与尾分号同样拒(镜像 parseStmtEof)。
func TestParseStatementUnrecognizedAndTrailing(t *testing.T) {
	wantStmtError(t, "postgresql", `foo bar`, 1)
	wantStmtError(t, "postgresql", `42`, 1)
	wantStmtError(t, "postgresql", `'str'`, 1)
	wantStmtError(t, "postgresql", `USE db`, 1)
	wantStmtError(t, "postgresql", `(SELECT 1) SELECT 2`, 12)
	wantStmtError(t, "postgresql", `SELECT 1; SELECT 2`, 9)
	// 空输入
	wantStmtError(t, "postgresql", ``, 1)
}

// 裸 VALUES(1) 解析成功(返回 *ast.Values),拒收交给 Classify——解析层按
// Calcite 文法接受(简报对齐规则)。VALUES 行集形态 + VALUE 单数拼写的方言门。
func TestParseValuesTopLevel(t *testing.T) {
	v := stmtAs[*ast.Values](t, mustStmt(t, "postgresql", `VALUES (1)`))
	if len(v.Rows) != 1 || len(v.Rows[0]) != 1 {
		t.Fatalf("rows = %v, want 1x1", v.Rows)
	}
	stmtAs[*ast.Values](t, mustStmt(t, "postgresql", `VALUES (1),(2)`))
	stmtAs[*ast.Values](t, mustStmt(t, "postgresql", `VALUES 1`))
	// VALUES + ORDER BY → OrderBy(Values) 包装(镜像 SqlOrderBy,Classify 仍拒)
	ob := stmtAs[*ast.OrderBy](t, mustStmt(t, "postgresql", `VALUES (1) ORDER BY 1`))
	if _, ok := ob.Query.(*ast.Values); !ok {
		t.Fatalf("order-by query type = %T, want *ast.Values", ob.Query)
	}
	// VALUE 单数拼写:DEFAULT 档(pg/trino)拒,MYSQL_5/LENIENT(mysql/hive/spark)接受
	wantStmtError(t, "postgresql", `VALUE (1)`, 1, "VALUE")
	stmtAs[*ast.Values](t, mustStmt(t, "mysql", `VALUE (1)`))
	stmtAs[*ast.Values](t, mustStmt(t, "hive", `VALUE (1)`))
}

// ---------------------------------------------------------------------------
// 关键字表锁定:javaCCKeyword 与 testdata/tokens.json 机械一致
// ---------------------------------------------------------------------------

func TestJavaCCKeywordTableLocked(t *testing.T) {
	bs, err := os.ReadFile(filepath.Join(repotool.Root(), "testdata", "tokens.json"))
	if err != nil {
		t.Fatalf("read testdata/tokens.json: %v", err)
	}
	var v struct {
		Keywords []string `json:"keywords"`
	}
	if err := json.Unmarshal(bs, &v); err != nil {
		t.Fatalf("parse testdata/tokens.json: %v", err)
	}
	if len(v.Keywords) == 0 {
		t.Fatal("tokens.json keywords is empty")
	}
	for _, kw := range v.Keywords {
		if !javaCCKeyword[kw] {
			t.Errorf("javaCCKeyword missing %q from tokens.json", kw)
		}
	}
	// 表外词不得混入
	for _, absent := range []string{"USE", "DIRECTORY", "FOO", "SELECTX"} {
		if javaCCKeyword[absent] {
			t.Errorf("javaCCKeyword has %q, not in tokens.json", absent)
		}
	}
}
