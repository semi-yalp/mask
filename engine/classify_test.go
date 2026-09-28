package engine

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/maskerr"
	"io.sqlmask/go/parser"
)

// ---------------------------------------------------------------------------
// 纯 AST 层:逐字镜像 AbstractCalciteDialectAdapter.classify / isQuery / unsupported
// ---------------------------------------------------------------------------

// 接受面:SELECT/INSERT(含 INSERT OVERWRITE)→ nil;ORDER_BY/WITH 包住
// query-like(Java isQuery:SELECT|WITH|ORDER_BY)→ nil;Plain CTAS → nil。
func TestClassifyAccepted(t *testing.T) {
	sel := &ast.Select{}
	accepted := []ast.Statement{
		sel,
		&ast.Insert{Source: sel},
		&ast.InsertOverwrite{Source: &ast.Values{}}, // kind 恒为 INSERT
		&ast.OrderBy{Query: sel},
		&ast.OrderBy{Query: &ast.With{Body: sel}},
		&ast.With{Body: sel},
		&ast.With{Body: &ast.OrderBy{Query: sel}},
		&ast.CreateTable{Query: sel},
	}
	for i, stmt := range accepted {
		if err := Classify(stmt, 0); err != nil {
			t.Fatalf("case %d (%T): Classify = %v, want nil", i, stmt, err)
		}
	}
}

// unsupported message 逐字格式(Java unsupported 三参拼接)。
const wantUnsupportedFmt = "statement %d: unsupported statement kind %s; only SELECT and WITH ... SELECT queries are supported in this version"

// 拒收面:顶层 Values/SetOp 一律 UNSUPPORTED(Java default 分支,含
// SqlOrderBy 包装规则的等价展开);ORDER_BY/WITH 包住非 query-like 时按被包
// 节点的 kind 报。
func TestClassifyUnsupported(t *testing.T) {
	cases := []struct {
		stmt ast.Statement
		kind string
	}{
		{&ast.Values{}, "VALUES"},
		{&ast.SetOp{Op: ast.Union}, "UNION"},
		{&ast.SetOp{Op: ast.Intersect}, "INTERSECT"},
		{&ast.SetOp{Op: ast.Except}, "EXCEPT"},
		// SqlOrderBy 包装非 query-like:isQuery(VALUES/SetOp)=false
		{&ast.OrderBy{Query: &ast.Values{}}, "VALUES"},
		{&ast.OrderBy{Query: &ast.SetOp{Op: ast.Union}}, "UNION"},
		// SqlWith body 非 query-like
		{&ast.With{Body: &ast.Values{}}, "VALUES"},
		{&ast.With{Body: &ast.SetOp{Op: ast.Except}}, "EXCEPT"},
		// CREATE TABLE 无 AS 查询
		{&ast.CreateTable{}, "CREATE_TABLE"},
		{&ast.CreateTable{Variant: ast.Multiset}, "CREATE_TABLE"},
	}
	for _, tc := range cases {
		err := Classify(tc.stmt, 0)
		var me *maskerr.Error
		if !errors.As(err, &me) {
			t.Fatalf("%T: Classify = %v, want *maskerr.Error", tc.stmt, err)
		}
		if me.Code != maskerr.UnsupportedStatement {
			t.Fatalf("%T: code = %s, want UNSUPPORTED_STATEMENT", tc.stmt, me.Code)
		}
		want := fmt.Sprintf(wantUnsupportedFmt, 0, tc.kind)
		if me.Message != want {
			t.Fatalf("%T: message = %q, want %q", tc.stmt, me.Message, want)
		}
	}
}

// ordinal 进入 message 前缀。
func TestClassifyOrdinal(t *testing.T) {
	err := Classify(&ast.Values{}, 2)
	var me *maskerr.Error
	if !errors.As(err, &me) {
		t.Fatalf("Classify = %v, want *maskerr.Error", err)
	}
	want := "statement 2: unsupported statement kind VALUES;"
	if !strings.HasPrefix(me.Message, want) {
		t.Fatalf("message = %q, want prefix %q", me.Message, want)
	}
}

// CREATE TABLE 变体拒绝(简报裁定:Variant!=Plain → UNSUPPORTED_STATEMENT,
// message 另述 variant 名;Query==nil 时仍按 Java classify 优先报 CREATE_TABLE)。
func TestClassifyCreateTableVariant(t *testing.T) {
	for _, v := range []ast.CreateTableVariant{ast.Replace, ast.Volatile, ast.Set, ast.Multiset} {
		err := Classify(&ast.CreateTable{Variant: v, Query: &ast.Select{}}, 0)
		var me *maskerr.Error
		if !errors.As(err, &me) {
			t.Fatalf("variant %v: Classify = %v, want *maskerr.Error", v, err)
		}
		if me.Code != maskerr.UnsupportedStatement {
			t.Fatalf("variant %v: code = %s, want UNSUPPORTED_STATEMENT", v, me.Code)
		}
		if !strings.Contains(strings.ToUpper(me.Message), strings.ToUpper(v.String())) {
			t.Fatalf("variant %v: message %q does not contain variant name", v, me.Message)
		}
	}
	// Query==nil 优先:即使带变体也报 CREATE_TABLE(镜像 Java classify 的唯一分支)
	err := Classify(&ast.CreateTable{Variant: ast.Replace}, 0)
	var me *maskerr.Error
	if !errors.As(err, &me) || !strings.Contains(me.Message, "CREATE_TABLE") {
		t.Fatalf("nil-query variant: message = %v, want CREATE_TABLE kind message", err)
	}
}

// ---------------------------------------------------------------------------
// 与 parser 集成:ParseStatement → Classify 的端到端分类面
// ---------------------------------------------------------------------------

func classifyParsed(t *testing.T, dialectName, sql string) error {
	t.Helper()
	prof, err := dialect.ByName(dialectName)
	if err != nil {
		t.Fatalf("ByName(%q): %v", dialectName, err)
	}
	stmt, err := parser.New(prof, sql).ParseStatement()
	if err != nil {
		t.Fatalf("ParseStatement(%q): unexpected error: %v", sql, err)
	}
	return Classify(stmt, 0)
}

func TestClassifyWithParser(t *testing.T) {
	for _, sql := range []string{
		`SELECT 1`,
		`(SELECT 1)`,
		`SELECT 1 ORDER BY 1 LIMIT 2`,
		`WITH c AS (SELECT 1) SELECT * FROM c`,
		`WITH RECURSIVE c AS (SELECT 1) SELECT * FROM c`,
		`WITH c AS (SELECT 1) SELECT 1 UNION SELECT 2 ORDER BY 1`, // ORDER_BY(WITH) → isQuery(WITH)=true
		`INSERT INTO t (a, b) SELECT 1, 2`,
		`INSERT INTO t VALUES (1,2),(3,4)`,
		`CREATE TABLE x AS SELECT 1`,
	} {
		if err := classifyParsed(t, "postgresql", sql); err != nil {
			t.Fatalf("Classify(%q) = %v, want nil", sql, err)
		}
	}
	for _, tc := range []struct {
		sql  string
		kind string
	}{
		{`VALUES (1)`, "VALUES"},
		{`VALUES (1) ORDER BY 1`, "VALUES"},
		{`SELECT 1 UNION SELECT 2`, "UNION"},
		{`SELECT 1 UNION SELECT 2 ORDER BY 1`, "UNION"},
		{`WITH c AS (SELECT 1) SELECT 1 UNION SELECT 2`, "UNION"}, // WITH body 非 query-like
		{`WITH c AS (SELECT 1) VALUES (1)`, "VALUES"},
		{`CREATE TABLE x`, "CREATE_TABLE"},
		{`CREATE MULTISET TABLE x`, "CREATE_TABLE"},
	} {
		err := classifyParsed(t, "postgresql", tc.sql)
		var me *maskerr.Error
		if !errors.As(err, &me) {
			t.Fatalf("Classify(%q) = %v, want *maskerr.Error", tc.sql, err)
		}
		if me.Code != maskerr.UnsupportedStatement {
			t.Fatalf("Classify(%q): code = %s, want UNSUPPORTED_STATEMENT", tc.sql, me.Code)
		}
		if !strings.Contains(me.Message, "unsupported statement kind "+tc.kind) {
			t.Fatalf("Classify(%q): message %q missing kind %s", tc.sql, me.Message, tc.kind)
		}
	}
	// INSERT OVERWRITE 仅 hive/sparksql 可解析;解析成功后 kind=INSERT → nil
	for _, d := range []string{"hive", "sparksql"} {
		if err := classifyParsed(t, d, `INSERT OVERWRITE TABLE t SELECT 1`); err != nil {
			t.Fatalf("Classify(INSERT OVERWRITE, %s) = %v, want nil", d, err)
		}
	}
	// CREATE TABLE 变体端到端
	err := classifyParsed(t, "postgresql", `CREATE OR REPLACE TABLE x AS SELECT 1`)
	var me *maskerr.Error
	if !errors.As(err, &me) || me.Code != maskerr.UnsupportedStatement ||
		!strings.Contains(strings.ToUpper(me.Message), "REPLACE") {
		t.Fatalf("Classify(CREATE OR REPLACE TABLE) = %v, want UNSUPPORTED with variant name", err)
	}
}
