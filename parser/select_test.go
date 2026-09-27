package parser

import (
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/lexer"
	"io.sqlmask/go/maskerr"
)

// 本文件为 Task 7(SELECT 核心与 FROM)的测试:沿用 testutil_test.go 的范式
// (newTestParser + normAST 清零 Pos 后 DeepEqual),期望 AST 全部字面构造。
//
// 边界口径(jar 实测,mask-core/target/sql-mask.jar + tpcds metadata):
//   - WHERE/GROUP BY/HAVING 仅在 FROM 分支(无 FROM 的 SELECT 不接受这些子句);
//   - GROUP BY 语境:ROLLUP(...) 按 jar 实测接受(fork 文法含 ROLLUP 产生式,
//     且语料 tpcds_deep_cases.sql [D9] 实际使用,命中决策 5「差分证明 Java
//     接受且语料需要,再按协议补」条款);GROUPING SETS 按决策 5 拒绝(Java
//     解析接受但语料零命中,差异记 T11 watchlist);CUBE(x) 按表达式规则自然
//     接受为普通函数调用;
//   - LATERAL 在 FROM 表引用位置拒绝(决策 5;jar 实测 Java 解析接受、语料
//     零命中,差异记 T11 watchlist);
//   - 别名停用词集合见 select.go aliasStopKw 注释(逐词 jar 实测校准)。

// ParseQueryStr 对 src 重新做词法分析后解析为完整查询并检查输入到 EOF。
// ParseQuery 本身只消费查询体(供子查询/派生表复用),整输入入口的残留
// token 检查由本助手承担(T8 的 ParseStatement 将内联同样的检查)。
func (p *Parser) ParseQueryStr(src string) (ast.Query, error) {
	toks, err := lexer.Lex(p.profile, src)
	if err != nil {
		return nil, err
	}
	p.toks, p.cur, p.lexErr = toks, 0, nil
	q, err := p.ParseQuery()
	if err != nil {
		return nil, err
	}
	if tok := p.curTok(); tok.Kind != lexer.EOF {
		return nil, p.errAt(tok.Pos, "unexpected token %q after query; expected end of input", tok.Text)
	}
	return q, nil
}

// mustQuery 解析成功则返回查询,失败则令测试致命退出。
func mustQuery(t *testing.T, dialectName, src string) ast.Query {
	t.Helper()
	got, err := newTestParser(t, dialectName).ParseQueryStr(src)
	if err != nil {
		t.Fatalf("ParseQueryStr(%q) with dialect %s: unexpected error: %v", src, dialectName, err)
	}
	return got
}

// wantQueryError 断言 src 解析失败,错误码为 maskerr.PARSE_ERROR,且 message
// 含 "Line 1, Column wantCol"(wantCol<=0 时只断言含 "Line")。
func wantQueryError(t *testing.T, dialectName, src string, wantCol int) {
	t.Helper()
	_, err := newTestParser(t, dialectName).ParseQueryStr(src)
	if err == nil {
		t.Fatalf("ParseQueryStr(%q) with dialect %s: expected PARSE_ERROR, got nil error", src, dialectName)
	}
	var me *maskerr.Error
	if !errors.As(err, &me) {
		t.Fatalf("ParseQueryStr(%q): error %T is not *maskerr.Error: %v", src, err, err)
	}
	if me.Code != maskerr.ParseError {
		t.Fatalf("ParseQueryStr(%q): error code = %s, want PARSE_ERROR (message: %s)", src, me.Code, me.Message)
	}
	needle := "Line 1"
	if wantCol > 0 {
		needle = fmt.Sprintf("Line 1, Column %d", wantCol)
	}
	if !strings.Contains(me.Message, needle) {
		t.Fatalf("ParseQueryStr(%q): message %q does not contain %q", src, me.Message, needle)
	}
}

// wantQueryErrorText 在 wantQueryError 基础上再断言 message 含指定片段
// (用于逐字固化决策 5 拒绝文案)。
func wantQueryErrorText(t *testing.T, dialectName, src string, wantCol int, text string) {
	t.Helper()
	_, err := newTestParser(t, dialectName).ParseQueryStr(src)
	if err == nil {
		t.Fatalf("ParseQueryStr(%q) with dialect %s: expected PARSE_ERROR, got nil error", src, dialectName)
	}
	var me *maskerr.Error
	if !errors.As(err, &me) {
		t.Fatalf("ParseQueryStr(%q): error %T is not *maskerr.Error: %v", src, err, err)
	}
	if me.Code != maskerr.ParseError {
		t.Fatalf("ParseQueryStr(%q): error code = %s, want PARSE_ERROR (message: %s)", src, me.Code, me.Message)
	}
	for _, needle := range []string{fmt.Sprintf("Line 1, Column %d", wantCol), text} {
		if !strings.Contains(me.Message, needle) {
			t.Fatalf("ParseQueryStr(%q): message %q does not contain %q", src, me.Message, needle)
		}
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:SELECT 项
// ---------------------------------------------------------------------------

// tn1 构造单段表名引用(测试字面构造辅助;期望 AST 本体仍逐字段写出)。
func tn1(name string) *ast.TableNameRef {
	return &ast.TableNameRef{Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: name}}}}}
}

func TestSelectItems(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Query
	}{
		// 简报:`SELECT * FROM t`
		{`SELECT * FROM t`, &ast.Select{
			Items: []ast.SelectItem{{Star: true}},
			From:  []ast.TableRef{tn1("t")},
		}},
		// 简报:`SELECT t.* FROM t`(StarQualifier=["t"])
		{`SELECT t.* FROM t`, &ast.Select{
			Items: []ast.SelectItem{{StarQualifier: []ast.IdentPart{{Value: "t"}}}},
			From:  []ast.TableRef{tn1("t")},
		}},
		// 引号段星限定符:值折算按 QuotedCasing(pg 恒 UNCHANGED),Quoted 保真
		{`SELECT "T".* FROM "T"`, &ast.Select{
			Items: []ast.SelectItem{{StarQualifier: []ast.IdentPart{{Value: "T", Quoted: true}}}},
			From: []ast.TableRef{&ast.TableNameRef{Parts: []ast.Identifier{
				{Parts: []ast.IdentPart{{Value: "T", Quoted: true}}}}}},
		}},
		// 简报:`SELECT 1`(无 FROM;From 保持 nil)
		{`SELECT 1`, &ast.Select{
			Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}},
		}},
		// 简报:`SELECT a x, b AS y`(隐式别名 + AS 别名)
		{`SELECT a x, b AS y`, &ast.Select{
			Items: []ast.SelectItem{
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
					Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}},
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
					Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}}},
			},
		}},
		// 简报:`SELECT DISTINCT a, b`
		{`SELECT DISTINCT a, b`, &ast.Select{
			Distinct: true,
			Items: []ast.SelectItem{
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}},
			},
		}},
		// ALL 量词接受并忽略(Distinct=false,与 T6 聚合限定词口径一致)
		{`SELECT ALL a FROM t`, &ast.Select{
			Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}}},
			From:  []ast.TableRef{tn1("t")},
		}},
		// 决策 4 最小非保留集可作列别名(jar 实测:year/top/overwrite 均接受)
		{`SELECT 1 year, 2 top, 3 overwrite`, &ast.Select{
			Items: []ast.SelectItem{
				{Expr: &ast.Literal{Kind: ast.Int, Text: "1"},
					Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "year"}}}},
				{Expr: &ast.Literal{Kind: ast.Int, Text: "2"},
					Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "top"}}}},
				{Expr: &ast.Literal{Kind: ast.Int, Text: "3"},
					Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "overwrite"}}}},
			},
		}},
		// jar 实测:`SELECT 1 all` 的尾随 ALL 按别名接受(非保留字,非量词位)
		{`SELECT 1 all`, &ast.Select{
			Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"},
				Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "all"}}}}},
		}},
		// 多段列引用与函数项混排;`SELECT (SELECT 1)` 标量子查询项
		{`SELECT s.t.c, f(x), (SELECT 1) FROM t`, &ast.Select{
			Items: []ast.SelectItem{
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{
					{Value: "s"}, {Value: "t"}, {Value: "c"}}}},
				{Expr: &ast.FunctionCall{
					Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "f"}}},
					Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
				{Expr: &ast.Subquery{Query: &ast.Select{
					Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}}}},
			},
			From: []ast.TableRef{tn1("t")},
		}},
	}
	for _, tc := range tests {
		got := mustQuery(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("query %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestSelectItemsDialectCasing 别名/列名大小写折算:pg 未引号折小写;mysql 原样。
func TestSelectItemsDialectCasing(t *testing.T) {
	pgGot := mustQuery(t, "postgresql", `SELECT Foo Bar`)
	pgWant := &ast.Select{Items: []ast.SelectItem{
		{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "foo"}}},
			Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "bar"}}}}}}
	if !reflect.DeepEqual(normAST(pgGot), normAST(pgWant)) {
		t.Fatalf("pg: got %#v", pgGot)
	}
	myGot := mustQuery(t, "mysql", "SELECT Foo Bar")
	myWant := &ast.Select{Items: []ast.SelectItem{
		{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "Foo"}}},
			Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "Bar"}}}}}}
	if !reflect.DeepEqual(normAST(myGot), normAST(myWant)) {
		t.Fatalf("mysql: got %#v", myGot)
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:FROM 链(逗号 = Comma、JOIN 系列)
// ---------------------------------------------------------------------------

func TestFromCommaJoins(t *testing.T) {
	// 简报:`FROM a, b, c` → 嵌套 Comma Join(左结合)
	got := mustQuery(t, "postgresql", `SELECT * FROM a, b, c`)
	want := &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.Join{Kind: ast.Comma,
			Left:  &ast.Join{Kind: ast.Comma, Left: tn1("a"), Right: tn1("b")},
			Right: tn1("c")}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("comma chain: got %#v", got)
	}

	// 混合逗号与 JOIN:左结合单循环(镜像 fork FromClause/JoinOrCommaTable:
	// JOIN 挂到整条左链上,右操作数恒为单个表引用)
	got = mustQuery(t, "postgresql", `SELECT * FROM a, b JOIN c ON a.x = c.x`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.Join{Kind: ast.Inner,
			Left:  &ast.Join{Kind: ast.Comma, Left: tn1("a"), Right: tn1("b")},
			Right: tn1("c"),
			On: &ast.Binary{Op: ast.Eq,
				Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}, {Value: "x"}}},
				Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}, {Value: "x"}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("mixed comma+join: got %#v", got)
	}
}

func TestJoinKinds(t *testing.T) {
	onAX := func() ast.Expr {
		return &ast.Binary{Op: ast.Eq,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}, {Value: "x"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}, {Value: "x"}}}}
	}
	tests := []struct {
		src  string
		kind ast.JoinKind
	}{
		{`SELECT * FROM a JOIN b ON a.x = b.x`, ast.Inner},
		{`SELECT * FROM a INNER JOIN b ON a.x = b.x`, ast.Inner},
		{`SELECT * FROM a LEFT JOIN b ON a.x = b.x`, ast.Left},
		{`SELECT * FROM a RIGHT JOIN b ON a.x = b.x`, ast.Right},
		{`SELECT * FROM a FULL JOIN b ON a.x = b.x`, ast.Full},
	}
	for _, tc := range tests {
		got := mustQuery(t, "postgresql", tc.src)
		want := &ast.Select{
			Items: []ast.SelectItem{{Star: true}},
			From: []ast.TableRef{&ast.Join{Kind: tc.kind,
				Left: tn1("a"), Right: tn1("b"), On: onAX()}},
		}
		if !reflect.DeepEqual(normAST(got), normAST(want)) {
			t.Fatalf("join %q: got %#v", tc.src, got)
		}
	}

	// 简报:`a LEFT OUTER JOIN b USING (x)`(OUTER 消费;Using 单列)
	got := mustQuery(t, "postgresql", `SELECT * FROM a LEFT OUTER JOIN b USING (x)`)
	want := &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.Join{Kind: ast.Left,
			Left:  tn1("a"),
			Right: tn1("b"),
			Using: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "x"}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("left outer using: got %#v", got)
	}

	// USING 多列
	got = mustQuery(t, "postgresql", `SELECT * FROM a JOIN b USING (x, y)`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.Join{Kind: ast.Inner,
			Left:  tn1("a"),
			Right: tn1("b"),
			Using: []ast.Identifier{
				{Parts: []ast.IdentPart{{Value: "x"}}},
				{Parts: []ast.IdentPart{{Value: "y"}}}}},
		},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("using multi: got %#v", got)
	}

	// 简报:`a CROSS JOIN b`(无连接条件;CROSS/COMMA 的 On/Using 恒空)
	got = mustQuery(t, "postgresql", `SELECT * FROM a CROSS JOIN b`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From:  []ast.TableRef{&ast.Join{Kind: ast.Cross, Left: tn1("a"), Right: tn1("b")}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("cross join: got %#v", got)
	}

	// 简报:`NATURAL JOIN`(Natural=true,Kind=Inner)与 NATURAL LEFT JOIN
	got = mustQuery(t, "postgresql", `SELECT * FROM a NATURAL JOIN b`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From:  []ast.TableRef{&ast.Join{Natural: true, Kind: ast.Inner, Left: tn1("a"), Right: tn1("b")}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("natural join: got %#v", got)
	}
	got = mustQuery(t, "postgresql", `SELECT * FROM a NATURAL LEFT JOIN b`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From:  []ast.TableRef{&ast.Join{Natural: true, Kind: ast.Left, Left: tn1("a"), Right: tn1("b")}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("natural left join: got %#v", got)
	}

	// JOIN 左结合嵌套 + OUTER 关键字
	got = mustQuery(t, "postgresql",
		`SELECT * FROM a JOIN b ON a.x = b.x LEFT OUTER JOIN c ON b.x = c.x`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.Join{Kind: ast.Left,
			Left:  &ast.Join{Kind: ast.Inner, Left: tn1("a"), Right: tn1("b"), On: onAX()},
			Right: tn1("c"),
			On: &ast.Binary{Op: ast.Eq,
				Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}, {Value: "x"}}},
				Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}, {Value: "x"}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("join chain: got %#v", got)
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:派生表
// ---------------------------------------------------------------------------

func TestDerivedTables(t *testing.T) {
	// 简报:`FROM (SELECT x FROM t) AS d (dx)`——断言 TableAlias.Columns
	got := mustQuery(t, "postgresql", `SELECT * FROM (SELECT x FROM t) AS d (dx)`)
	want := &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.DerivedTable{
			Query: &ast.Select{
				Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}},
				From:  []ast.TableRef{tn1("t")},
			},
			Alias: &ast.TableAlias{
				Name:    ast.Identifier{Parts: []ast.IdentPart{{Value: "d"}}},
				Columns: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "dx"}}}},
			},
		}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("derived table AS d (dx): got %#v", got)
	}

	// 显式 AS 无列名清单
	got = mustQuery(t, "postgresql", `SELECT * FROM (SELECT 1) AS d`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.DerivedTable{
			Query: &ast.Select{Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}},
			Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "d"}}}},
		}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("derived table AS d: got %#v", got)
	}

	// 隐式别名与无别名(jar 实测两者均解析接受)
	got = mustQuery(t, "postgresql", `SELECT * FROM (SELECT 1) d`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.DerivedTable{
			Query: &ast.Select{Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}},
			Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "d"}}}},
		}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("derived table implicit alias: got %#v", got)
	}
	got = mustQuery(t, "postgresql", `SELECT * FROM (SELECT 1)`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.DerivedTable{
			Query: &ast.Select{Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}},
		}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("derived table no alias: got %#v", got)
	}

	// 派生表作 JOIN 右操作数
	got = mustQuery(t, "postgresql", `SELECT * FROM a JOIN (SELECT x FROM u) s ON a.x = s.x`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Star: true}},
		From: []ast.TableRef{&ast.Join{Kind: ast.Inner,
			Left: tn1("a"),
			Right: &ast.DerivedTable{
				Query: &ast.Select{
					Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}},
					From:  []ast.TableRef{tn1("u")},
				},
				Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "s"}}}},
			},
			On: &ast.Binary{Op: ast.Eq,
				Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}, {Value: "x"}}},
				Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "s"}, {Value: "x"}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("join derived: got %#v", got)
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:WHERE/GROUP BY/HAVING 组合 + GROUP BY 语境(决策 5)
// ---------------------------------------------------------------------------

func TestWhereGroupHaving(t *testing.T) {
	got := mustQuery(t, "postgresql",
		`SELECT a, COUNT(b) FROM t WHERE c > 0 GROUP BY a HAVING COUNT(b) > 1`)
	want := &ast.Select{
		Items: []ast.SelectItem{
			{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
			{Expr: &ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "count"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}}},
		},
		From: []ast.TableRef{tn1("t")},
		Where: &ast.Binary{Op: ast.Gt,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}}},
			Right: &ast.Literal{Kind: ast.Int, Text: "0"}},
		GroupBy: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
		Having: &ast.Binary{Op: ast.Gt,
			Left: &ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "count"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
			Right: &ast.Literal{Kind: ast.Int, Text: "1"}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("where/group/having: got %#v", got)
	}
}

// TestGroupByRollupAccepted GROUP BY 语境的 ROLLUP:jar 实测 Java 解析接受
// (fork GroupingElementList 含 ROLLUP 产生式)且语料 tpcds_deep_cases.sql [D9]
// 实际使用——按决策 5「差分证明 Java 接受且语料需要,再按协议补」条款,以
// ast.FunctionCall 形态接受;T6 的表达式语境拒绝不受影响(expr_test.go 保留)。
func TestGroupByRollupAccepted(t *testing.T) {
	got := mustQuery(t, "postgresql", `SELECT a FROM t GROUP BY ROLLUP (a)`)
	want := &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}}},
		From:  []ast.TableRef{tn1("t")},
		GroupBy: []ast.Expr{&ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "rollup"}}},
			Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("group by rollup: got %#v", got)
	}

	got = mustQuery(t, "postgresql", `SELECT a FROM t GROUP BY ROLLUP (a, b), a`)
	want = &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}}},
		From:  []ast.TableRef{tn1("t")},
		GroupBy: []ast.Expr{
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "rollup"}}},
				Args: []ast.Expr{
					&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
					&ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
			&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
		},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("group by rollup multi: got %#v", got)
	}
}

// TestGroupByCubeAsFunction CUBE(x) 在 GROUP BY 列表按普通函数表达式自然
// 处理(简报明示;表达式语境行为与 T6 一致)。
func TestGroupByCubeAsFunction(t *testing.T) {
	got := mustQuery(t, "postgresql", `SELECT a FROM t GROUP BY CUBE(a)`)
	want := &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}}},
		From:  []ast.TableRef{tn1("t")},
		GroupBy: []ast.Expr{&ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "cube"}}},
			Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("group by cube: got %#v", got)
	}
}

// ---------------------------------------------------------------------------
// 子查询获得完整 SELECT 能力(parseQueryMinimal → ParseQuery 替换的回归)
// ---------------------------------------------------------------------------

func TestSubqueryFullSelect(t *testing.T) {
	// EXISTS 内的完整 SELECT(含 FROM/WHERE)
	got := mustExpr(t, "postgresql", `EXISTS (SELECT x FROM u WHERE u.y = t.x)`)
	want1 := &ast.Exists{Subquery: &ast.Subquery{Query: &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}},
		From:  []ast.TableRef{tn1("u")},
		Where: &ast.Binary{Op: ast.Eq,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "u"}, {Value: "y"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "t"}, {Value: "x"}}}},
	}}}
	if !reflect.DeepEqual(normAST(got), normAST(want1)) {
		t.Fatalf("exists full select: got %#v", got)
	}

	// IN 子查询含 FROM
	got = mustExpr(t, "postgresql", `a IN (SELECT b FROM u)`)
	want2 := &ast.InPred{
		Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
		Subquery: &ast.Subquery{Query: &ast.Select{
			Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
			From:  []ast.TableRef{tn1("u")},
		}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want2)) {
		t.Fatalf("in subquery full select: got %#v", got)
	}

	// 查询内 WHERE 的 IN 子查询再嵌派生表能力(经 ParseQuery 入口)
	gotQ := mustQuery(t, "postgresql", `SELECT 1 FROM t WHERE a IN (SELECT b FROM u)`)
	wantQ := &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}},
		From:  []ast.TableRef{tn1("t")},
		Where: &ast.InPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Subquery: &ast.Subquery{Query: &ast.Select{
				Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
				From:  []ast.TableRef{tn1("u")},
			}},
		},
	}
	if !reflect.DeepEqual(normAST(gotQ), normAST(wantQ)) {
		t.Fatalf("where in subquery: got %#v", gotQ)
	}
}

// ---------------------------------------------------------------------------
// 表名与表别名
// ---------------------------------------------------------------------------

func TestTableNamesAndAliases(t *testing.T) {
	tests := []struct {
		src  string
		want *ast.TableNameRef
	}{
		{`SELECT * FROM t x`, &ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "t"}}}},
			Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
		{`SELECT * FROM t AS x`, &ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "t"}}}},
			Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
		// 简报列名清单形态:t AS x (a, b) 与隐式别名 + 列清单
		{`SELECT * FROM t AS x (a, b)`, &ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "t"}}}},
			Alias: &ast.TableAlias{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
				Columns: []ast.Identifier{
					{Parts: []ast.IdentPart{{Value: "a"}}},
					{Parts: []ast.IdentPart{{Value: "b"}}}}}}},
		{`SELECT * FROM t x (a)`, &ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "t"}}}},
			Alias: &ast.TableAlias{
				Name:    ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
				Columns: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "a"}}}}}}},
		// 2–4 段名(含引号段混排;语料实况 tpcds."public"."customer")
		{`SELECT * FROM s.t`, &ast.TableNameRef{
			Parts: []ast.Identifier{
				{Parts: []ast.IdentPart{{Value: "s"}}},
				{Parts: []ast.IdentPart{{Value: "t"}}}}}},
		{`SELECT * FROM tpcds."public".customer`, &ast.TableNameRef{
			Parts: []ast.Identifier{
				{Parts: []ast.IdentPart{{Value: "tpcds"}}},
				{Parts: []ast.IdentPart{{Value: "public", Quoted: true}}},
				{Parts: []ast.IdentPart{{Value: "customer"}}}}}},
		{`SELECT * FROM a.b.c.d`, &ast.TableNameRef{
			Parts: []ast.Identifier{
				{Parts: []ast.IdentPart{{Value: "a"}}},
				{Parts: []ast.IdentPart{{Value: "b"}}},
				{Parts: []ast.IdentPart{{Value: "c"}}},
				{Parts: []ast.IdentPart{{Value: "d"}}}}}},
		// 决策 4 最小非保留集可作表别名(jar 实测 top/year 均接受)
		{`SELECT 1 FROM t top`, &ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "t"}}}},
			Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "top"}}}}}},
		{`SELECT 1 FROM t year`, &ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "t"}}}},
			Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "year"}}}}}},
	}
	for _, tc := range tests {
		got := mustQuery(t, "postgresql", tc.src)
		sel, ok := got.(*ast.Select)
		if !ok || len(sel.From) != 1 {
			t.Fatalf("query %q: expected single-FROM Select, got %#v", tc.src, got)
		}
		if !reflect.DeepEqual(normAST(sel.From[0]), normAST(tc.want)) {
			t.Fatalf("query %q: got %#v, want %#v", tc.src, sel.From[0], tc.want)
		}
	}

	// mysql:反引号引号段 + 未引号原样
	myGot := mustQuery(t, "mysql", "SELECT Foo FROM `Bar` AS `Baz` (Q)")
	myWant := &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "Foo"}}}}},
		From: []ast.TableRef{&ast.TableNameRef{
			Parts: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "Bar", Quoted: true}}}},
			Alias: &ast.TableAlias{
				Name:    ast.Identifier{Parts: []ast.IdentPart{{Value: "Baz", Quoted: true}}},
				Columns: []ast.Identifier{{Parts: []ast.IdentPart{{Value: "Q"}}}}},
		}},
	}
	if !reflect.DeepEqual(normAST(myGot), normAST(myWant)) {
		t.Fatalf("mysql aliases: got %#v", myGot)
	}
}

// ---------------------------------------------------------------------------
// 拒绝面(错误码 PARSE_ERROR + 位置;决策 5 逐字文案另断言)
// ---------------------------------------------------------------------------

func TestSelectRejections(t *testing.T) {
	tests := []struct {
		src string
		col int // 期望 "Line 1, Column N";<=0 只断言含 "Line 1"
	}{
		// jar 实测:无 FROM 的 SELECT 不接受 WHERE/GROUP BY/HAVING
		// (fork SqlSelect 产生式把三个子句放在 FROM 分支内)
		{`SELECT 1 WHERE 1 = 0`, 10},
		{`SELECT 1 GROUP BY 2`, 10},
		{`SELECT 1 HAVING 1 = 1`, 10},
		// SELECT 后必须有选择项(子句关键字不能起项)
		{`SELECT FROM t`, 8},
		{`SELECT DISTINCT`, 0},
		// 表名段数上限(简报 TableName 1–4 段;第 5 段报错)
		{`SELECT 1 FROM a.b.c.d.e`, 23},
		// JOIN 条件缺失 / CROSS、NATURAL 带条件(简报文法;jar 实测 Java 解析
		// 接受这三种形态,Go 拒绝——差异记 T11 watchlist,语料零命中)
		{`SELECT 1 FROM t JOIN u`, 0},
		{`SELECT 1 FROM t CROSS JOIN u ON 1 = 1`, 30},
		{`SELECT 1 FROM t NATURAL JOIN u ON 1 = 1`, 32},
		{`SELECT 1 FROM t JOIN u USING ()`, 31},
		{`SELECT 1 FROM t JOIN`, 0},
		// 别名停用词(jar 实测逐词校准,见 select.go aliasStopKw)
		{`SELECT 1 AS where`, 13},
		{`SELECT 1 when`, 10},
		{`SELECT 1 case`, 10},
		{`SELECT 1 null`, 10},
		{`SELECT 1 FROM t AS where`, 20},
		// 残片:别名后残留 token
		{`SELECT 1 2`, 10},
		{`SELECT 1 x 2`, 12},
	}
	for _, tc := range tests {
		wantQueryError(t, "postgresql", tc.src, tc.col)
	}
}

// TestDecision5Rejections 决策 5 在查询层的两个显式拒绝点(文案逐字):
//   - GROUPING SETS 在 GROUP BY 位置(SETS 为拒绝点;Java 解析接受、语料零
//     命中,差异记 T11 watchlist);
//   - LATERAL 在 FROM 表引用位置(与 T6 表达式层同文案;Java 解析接受、语料
//     零命中,差异记 T11 watchlist)。
func TestDecision5Rejections(t *testing.T) {
	wantQueryErrorText(t, "postgresql",
		`SELECT 1 FROM t GROUP BY GROUPING SETS (a)`, 35,
		"Incorrect syntax near the keyword 'SETS'")
	wantQueryErrorText(t, "postgresql",
		`SELECT 1 FROM LATERAL (SELECT 2) d`, 15,
		"Incorrect syntax near the keyword 'LATERAL'")
	wantQueryErrorText(t, "postgresql",
		`SELECT 1 FROM lateral (SELECT 2) d`, 15,
		"Incorrect syntax near the keyword 'LATERAL'")
	// 引号标识符永不匹配关键字:决策 5 拒绝不影响 "lateral" 作表名
	got := mustQuery(t, "postgresql", `SELECT 1 FROM "lateral"`)
	want := &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}},
		From: []ast.TableRef{&ast.TableNameRef{Parts: []ast.Identifier{
			{Parts: []ast.IdentPart{{Value: "lateral", Quoted: true}}}}}},
	}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("quoted lateral table: got %#v", got)
	}
}

// TestQueryLexErrorPassthrough 词法错误经 ParseQueryStr 原样透传(与 T6
// ParseExpr 同口径)。
func TestQueryLexErrorPassthrough(t *testing.T) {
	_, err := newTestParser(t, "postgresql").ParseQueryStr(`SELECT 1 FROM "t`)
	if err == nil {
		t.Fatal("expected lexical error, got nil")
	}
	var me *maskerr.Error
	if !errors.As(err, &me) || me.Code != maskerr.ParseError {
		t.Fatalf("error %v is not PARSE_ERROR", err)
	}
	if !strings.Contains(me.Message, "Lexical error") {
		t.Fatalf("message %q does not contain Lexical error", me.Message)
	}
}
