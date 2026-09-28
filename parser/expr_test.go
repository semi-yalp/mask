package parser

import (
	"errors"
	"reflect"
	"strings"
	"testing"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/maskerr"
)

// TestPrecedence 为简报给出的范式样例:定下全部测试的书写范式
// (newTestParser + ParseExprStr + 期望 AST 字面构造 + normAST 清零 Pos 后
// DeepEqual)。
func TestPrecedence(t *testing.T) {
	got, err := newTestParser(t, "postgresql").ParseExprStr(`a + b * c`)
	if err != nil {
		t.Fatal(err)
	}
	want := &ast.Binary{Op: ast.Add,
		Left: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
		Right: &ast.Binary{Op: ast.Mul,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
}

// TestLiterals 字面量五类:整数/小数/近似数/字符串/布尔,另含 NULL
// (决策 6 字面量支持面;Text 为源文片段,引号串含引号原文)。
func TestLiterals(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`1`, &ast.Literal{Kind: ast.Int, Text: "1"}},
		{`123456789`, &ast.Literal{Kind: ast.Int, Text: "123456789"}},
		{`1.5`, &ast.Literal{Kind: ast.Decimal, Text: "1.5"}},
		{`1.`, &ast.Literal{Kind: ast.Decimal, Text: "1."}},
		{`.5`, &ast.Literal{Kind: ast.Decimal, Text: ".5"}},
		{`1.2E-3`, &ast.Literal{Kind: ast.Approx, Text: "1.2E-3"}},
		{`1e10`, &ast.Literal{Kind: ast.Approx, Text: "1e10"}},
		{`'it''s'`, &ast.Literal{Kind: ast.String, Text: "'it''s'"}},
		{`'plain'`, &ast.Literal{Kind: ast.String, Text: "'plain'"}},
		{`TRUE`, &ast.Literal{Kind: ast.True, Text: "TRUE"}},
		{`false`, &ast.Literal{Kind: ast.False, Text: "false"}},
		{`NULL`, &ast.Literal{Kind: ast.Null, Text: "NULL"}},
		{`null`, &ast.Literal{Kind: ast.Null, Text: "null"}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("literal %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestDatetimeIntervalLiterals DATE/TIME/TIMESTAMP/INTERVAL 字面量(决策 6)。
// Literal.Text 存引号串源文(含引号);IntervalLit.Text 存去引号正文。
func TestDatetimeIntervalLiterals(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`DATE '2020-01-02'`, &ast.Literal{Kind: ast.Date, Text: "'2020-01-02'"}},
		{`date '2020-01-02'`, &ast.Literal{Kind: ast.Date, Text: "'2020-01-02'"}},
		{`TIME '12:34:56'`, &ast.Literal{Kind: ast.Time, Text: "'12:34:56'"}},
		{`TIMESTAMP '2020-01-02 12:34:56'`, &ast.Literal{Kind: ast.Timestamp, Text: "'2020-01-02 12:34:56'"}},
		{`INTERVAL '1' DAY`, &ast.Literal{Kind: ast.Interval, Text: "'1'",
			Interval: ast.IntervalLit{Text: "1", StartUnit: "DAY"}}},
		{`INTERVAL '2:30' HOUR TO MINUTE`, &ast.Literal{Kind: ast.Interval, Text: "'2:30'",
			Interval: ast.IntervalLit{Text: "2:30", StartUnit: "HOUR", EndUnit: "MINUTE"}}},
		{`INTERVAL '1' YEAR TO MONTH`, &ast.Literal{Kind: ast.Interval, Text: "'1'",
			Interval: ast.IntervalLit{Text: "1", StartUnit: "YEAR", EndUnit: "MONTH"}}},
		{`interval '-3' second`, &ast.Literal{Kind: ast.Interval, Text: "'-3'",
			Interval: ast.IntervalLit{Text: "-3", StartUnit: "SECOND"}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("literal %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
	// 非法形态:INTERVAL 后缺单位、非法 TO 组合。
	wantExprError(t, "postgresql", `INTERVAL '1'`, 12)
	wantExprError(t, "postgresql", `INTERVAL '1' YEAR TO HOUR`, 22)
	wantExprError(t, "postgresql", `DATE 1`, 6)
}

// TestMultiPartIdentifiers 多段名 a.b.c 与 "A"."b"(引号内不折大小写)。
func TestMultiPartIdentifiers(t *testing.T) {
	tests := []struct {
		dialect string
		src     string
		want    ast.Expr
	}{
		{"postgresql", `a.b.c`, &ast.Identifier{Parts: []ast.IdentPart{
			{Value: "a"}, {Value: "b"}, {Value: "c"}}}},
		{"postgresql", `"A"."b"`, &ast.Identifier{Parts: []ast.IdentPart{
			{Value: "A", Quoted: true}, {Value: "b", Quoted: true}}}},
		// 引号标识符内部双写转义还原为单引号字符。
		{"postgresql", `"a""b"`, &ast.Identifier{Parts: []ast.IdentPart{
			{Value: "a\"b", Quoted: true}}}},
		{"postgresql", `t.c`, &ast.Identifier{Parts: []ast.IdentPart{
			{Value: "t"}, {Value: "c"}}}},
		// mysql:反引号引用;未引号原样。
		{"mysql", "A.b", &ast.Identifier{Parts: []ast.IdentPart{
			{Value: "A"}, {Value: "b"}}}},
		{"mysql", "`A`.`b`", &ast.Identifier{Parts: []ast.IdentPart{
			{Value: "A", Quoted: true}, {Value: "b", Quoted: true}}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, tc.dialect, tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("ident %q (dialect %s): got %#v, want %#v", tc.src, tc.dialect, got, tc.want)
		}
	}
}

// TestIdentifierCasing 标识符大小写折算:pg/trino/hive/sparksql 未引号折小写,
// mysql 原样;引号内恒不折(决策 3)。
func TestIdentifierCasing(t *testing.T) {
	tests := []struct {
		dialect string
		src     string
		want    []ast.IdentPart
	}{
		{"postgresql", `Foo`, []ast.IdentPart{{Value: "foo"}}},
		{"postgresql", `Foo.Bar`, []ast.IdentPart{{Value: "foo"}, {Value: "bar"}}},
		{"postgresql", `"Foo"`, []ast.IdentPart{{Value: "Foo", Quoted: true}}},
		{"trino", `FOO`, []ast.IdentPart{{Value: "foo"}}},
		{"mysql", `Foo`, []ast.IdentPart{{Value: "Foo"}}},
		{"mysql", "Foo.Bar", []ast.IdentPart{{Value: "Foo"}, {Value: "Bar"}}},
		{"mysql", "`Foo`", []ast.IdentPart{{Value: "Foo", Quoted: true}}},
		{"hive", "Foo", []ast.IdentPart{{Value: "foo"}}},
		{"hive", "`Foo`", []ast.IdentPart{{Value: "Foo", Quoted: true}}},
		{"sparksql", `FOO`, []ast.IdentPart{{Value: "foo"}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, tc.dialect, tc.src)
		want := &ast.Identifier{Parts: tc.want}
		if !reflect.DeepEqual(normAST(got), normAST(want)) {
			t.Fatalf("casing %q (dialect %s): got %#v, want %#v", tc.src, tc.dialect, got, tc.want)
		}
	}
}

// TestFunctionCalls COUNT(*)、COUNT(DISTINCT x)、f(a, b)、f()、f(ALL x)。
func TestFunctionCalls(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`COUNT(*)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "count"}}},
			Star: true}},
		{`count(DISTINCT x)`, &ast.FunctionCall{
			Name:     ast.Identifier{Parts: []ast.IdentPart{{Value: "count"}}},
			Distinct: true,
			Args:     []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
		{`f(a, b)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "f"}}},
			Args: []ast.Expr{
				&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
				&ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}}},
		{`f()`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "f"}}}}},
		{`f(ALL x)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "f"}}},
			Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
		{`a.b.f(x)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{
				{Value: "a"}, {Value: "b"}, {Value: "f"}}},
			Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("call %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestCase 两形态:searched 与 simple;ELSE 可缺省(Else nil)。
func TestCase(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`CASE WHEN a THEN 1 WHEN b THEN 2 ELSE 3 END`, &ast.Case{
			Whens: []ast.When{
				{Cond: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
					Then: &ast.Literal{Kind: ast.Int, Text: "1"}},
				{Cond: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
					Then: &ast.Literal{Kind: ast.Int, Text: "2"}},
			},
			Else: &ast.Literal{Kind: ast.Int, Text: "3"}}},
		{`CASE x WHEN 1 THEN 'a' ELSE 'b' END`, &ast.Case{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
			Whens: []ast.When{
				{Cond: &ast.Literal{Kind: ast.Int, Text: "1"},
					Then: &ast.Literal{Kind: ast.String, Text: "'a'"}},
			},
			Else: &ast.Literal{Kind: ast.String, Text: "'b'"}}},
		{`CASE WHEN a THEN 1 END`, &ast.Case{
			Whens: []ast.When{
				{Cond: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
					Then: &ast.Literal{Kind: ast.Int, Text: "1"}},
			}}},
		// WHEN 条件与 THEN 结果均为完整表达式(谓词层可达)。
		{`CASE WHEN a = 1 THEN x + 1 END`, &ast.Case{
			Whens: []ast.When{
				{Cond: &ast.Binary{Op: ast.Eq,
					Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
					Right: &ast.Literal{Kind: ast.Int, Text: "1"}},
					Then: &ast.Binary{Op: ast.Add,
						Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
						Right: &ast.Literal{Kind: ast.Int, Text: "1"}}},
			}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("case %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
	// 无 WHEN 分支与缺 THEN 均报错。
	wantExprError(t, "postgresql", `CASE x END`, 8)
	wantExprError(t, "postgresql", `CASE WHEN a THEN END`, 20)
}

// TestCast CAST(x AS type);类型名清单与规范化见决策 8。
func TestCast(t *testing.T) {
	got := mustExpr(t, "postgresql", `CAST(x AS DECIMAL(10, 2))`)
	want := &ast.Cast{
		Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
		Type: ast.TypeSpec{Name: "DECIMAL",
			Precision: &ast.Literal{Kind: ast.Int, Text: "10"},
			Scale:     &ast.Literal{Kind: ast.Int, Text: "2"}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("cast: got %#v, want %#v", got, want)
	}

	typTests := []struct {
		src  string
		name string
	}{
		{`CAST(a AS INT)`, "INTEGER"},
		{`CAST(a AS INTEGER)`, "INTEGER"},
		{`CAST(a AS BIGINT)`, "BIGINT"},
		{`CAST(a AS SMALLINT)`, "SMALLINT"},
		{`CAST(a AS TINYINT)`, "TINYINT"},
		{`CAST(a AS REAL)`, "REAL"},
		{`CAST(a AS FLOAT)`, "FLOAT"},
		{`CAST(a AS FLOAT(24))`, "FLOAT"},
		{`CAST(a AS DOUBLE PRECISION)`, "DOUBLE PRECISION"},
		{`CAST(a AS DEC)`, "DECIMAL"},
		{`CAST(a AS NUMERIC)`, "NUMERIC"},
		{`CAST(a AS NUMERIC(8))`, "NUMERIC"},
		{`CAST(a AS CHAR)`, "CHAR"},
		{`CAST(a AS CHARACTER(5))`, "CHAR"},
		{`CAST(a AS VARCHAR(20))`, "VARCHAR"},
		{`CAST(a AS CHARACTER VARYING(20))`, "VARCHAR"},
		{`CAST(a AS DATE)`, "DATE"},
		{`CAST(a AS TIME(6))`, "TIME"},
		{`CAST(a AS TIME WITH TIME ZONE)`, "TIME WITH TIME ZONE"},
		{`CAST(a AS TIME WITHOUT TIME ZONE)`, "TIME WITHOUT TIME ZONE"},
		{`CAST(a AS TIMESTAMP(3) WITH TIME ZONE)`, "TIMESTAMP WITH TIME ZONE"},
		{`CAST(a AS BINARY(16))`, "BINARY"},
		{`CAST(a AS BINARY VARYING(16))`, "VARBINARY"},
		{`CAST(a AS VARBINARY)`, "VARBINARY"},
		{`CAST(a AS INTERVAL HOUR TO SECOND)`, "INTERVAL HOUR TO SECOND"},
		{`CAST(a AS INTERVAL DAY)`, "INTERVAL DAY"},
	}
	for _, tc := range typTests {
		got := mustExpr(t, "postgresql", tc.src)
		c, ok := got.(*ast.Cast)
		if !ok {
			t.Fatalf("%q: got %T, want *ast.Cast", tc.src, got)
		}
		if c.Type.Name != tc.name {
			t.Fatalf("%q: type name = %q, want %q", tc.src, c.Type.Name, tc.name)
		}
	}
	// 决策 8 清单外的类型名报 PARSE_ERROR。
	wantExprError(t, "postgresql", `CAST(x AS FOO)`, 11)
	wantExprError(t, "postgresql", `CAST(x AS FOOBAR(1))`, 11)
}

// TestExists EXISTS (SELECT 1)。
func TestExists(t *testing.T) {
	got := mustExpr(t, "postgresql", `EXISTS (SELECT 1)`)
	want := &ast.Exists{Subquery: &ast.Subquery{Query: &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
	// EXISTS 后必须是子查询。
	wantExprError(t, "postgresql", `EXISTS (a)`, 9)
	wantExprError(t, "postgresql", `EXISTS a`, 8)
}

// TestScalarSubquery (SELECT 1) 标量子查询与多列形态。
func TestScalarSubquery(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`(SELECT 1)`, &ast.Subquery{Query: &ast.Select{
			Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}}}},
		{`(SELECT a, b)`, &ast.Subquery{Query: &ast.Select{
			Items: []ast.SelectItem{
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
				{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}},
			}}}},
		{`(SELECT DISTINCT 1)`, &ast.Subquery{Query: &ast.Select{
			Distinct: true,
			Items:    []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}}}},
		// 括号表达式与子查询的区分。
		{`((1))`, &ast.Literal{Kind: ast.Int, Text: "1"}},
		{`(1 + 2) * 3`, &ast.Binary{Op: ast.Mul,
			Left: &ast.Binary{Op: ast.Add,
				Left:  &ast.Literal{Kind: ast.Int, Text: "1"},
				Right: &ast.Literal{Kind: ast.Int, Text: "2"}},
			Right: &ast.Literal{Kind: ast.Int, Text: "3"}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("subquery %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestParams 动态参数 ?(Index=-1)与 ?N(决策 6)。
func TestParams(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`?`, &ast.Param{Index: -1}},
		{`?1`, &ast.Param{Index: 1}},
		{`?42`, &ast.Param{Index: 42}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("param %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestUnary 一元 -x +y(决策 7:一元紧贴 primary,高于乘除模)。
func TestUnary(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		// -x + y:前导 - 为一元,中缀 + 为二元(二元形态;一元 + 由 +y 覆盖)。
		{`-x + y`, &ast.Binary{Op: ast.Add,
			Left: &ast.Unary{Op: ast.Neg,
				Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}}}},
		{`+y`, &ast.Unary{Op: ast.Plus,
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}}}},
		{`- -1`, &ast.Unary{Op: ast.Neg,
			Operand: &ast.Unary{Op: ast.Neg,
				Operand: &ast.Literal{Kind: ast.Int, Text: "1"}}}},
		{`-a * b`, &ast.Binary{Op: ast.Mul,
			Left: &ast.Unary{Op: ast.Neg,
				Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("unary %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestPrecedenceConcat a || b(决策 7:|| 落在加减层)及左结合链。
func TestPrecedenceConcat(t *testing.T) {
	got := mustExpr(t, "postgresql", `a || b`)
	want := &ast.Binary{Op: ast.Concat,
		Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
		Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
	// 同级左结合:a - b - c → ((a - b) - c)。
	got = mustExpr(t, "postgresql", `a - b - c`)
	want = &ast.Binary{Op: ast.Sub,
		Left: &ast.Binary{Op: ast.Sub,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}},
		Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
}

// TestPrecedenceComparisonAnd a = b AND c:比较先结合,AND 在上层。
func TestPrecedenceComparisonAnd(t *testing.T) {
	got := mustExpr(t, "postgresql", `a = b AND c`)
	want := &ast.Binary{Op: ast.And,
		Left: &ast.Binary{Op: ast.Eq,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}},
		Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
}

// TestPrecedenceNotOr NOT a OR b:NOT 紧贴操作数,OR 在最外层。
func TestPrecedenceNotOr(t *testing.T) {
	var got, want ast.Expr
	got = mustExpr(t, "postgresql", `NOT a OR b`)
	want = &ast.Binary{Op: ast.Or,
		Left: &ast.Unary{Op: ast.Not,
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
		Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
	// NOT a = b → NOT (a = b):NOT 低于谓词层。
	got = mustExpr(t, "postgresql", `NOT a = b`)
	want = &ast.Unary{Op: ast.Not,
		Operand: &ast.Binary{Op: ast.Eq,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
}

// TestPredicates 谓词层全形态:BETWEEN/IN(列表、子查询、NOT)/LIKE(含
// ESCAPE、NOT)/IS NULL/TRUE/FALSE/UNKNOWN/IS [NOT] DISTINCT FROM。
func TestPredicates(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`a BETWEEN x AND y`, &ast.Between{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Low:     &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
			High:    &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}}}},
		{`a NOT BETWEEN x AND y`, &ast.Between{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Low:     &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
			High:    &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}},
			Negated: true}},
		{`a BETWEEN SYMMETRIC x AND y`, &ast.Between{
			Operand:   &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Low:       &ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}},
			High:      &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}},
			Symmetric: true}},
		{`a NOT IN (1, 2)`, &ast.InPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Negated: true,
			List: []ast.Expr{
				&ast.Literal{Kind: ast.Int, Text: "1"},
				&ast.Literal{Kind: ast.Int, Text: "2"}}}},
		{`a IN (1, 2)`, &ast.InPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			List: []ast.Expr{
				&ast.Literal{Kind: ast.Int, Text: "1"},
				&ast.Literal{Kind: ast.Int, Text: "2"}}}},
		{`a IN (SELECT 1)`, &ast.InPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Subquery: &ast.Subquery{Query: &ast.Select{
				Items: []ast.SelectItem{{Expr: &ast.Literal{Kind: ast.Int, Text: "1"}}}}}}},
		{`a LIKE 'x' ESCAPE '!'`, &ast.LikePred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Pattern: &ast.Literal{Kind: ast.String, Text: "'x'"},
			Kind:    ast.Like,
			Escape:  &ast.Literal{Kind: ast.String, Text: "'!'"}}},
		{`a NOT LIKE b`, &ast.LikePred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Pattern: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
			Kind:    ast.Like,
			Negated: true}},
		{`a ILIKE b`, &ast.LikePred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Pattern: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
			Kind:    ast.ILike}},
		{`a SIMILAR TO b`, &ast.LikePred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Pattern: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
			Kind:    ast.Similar}},
		{`a IS NOT NULL`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Negated: true,
			What:    ast.IsNull}},
		{`a IS NULL`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			What:    ast.IsNull}},
		{`a IS TRUE`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			What:    ast.IsTrue}},
		{`a IS NOT FALSE`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Negated: true,
			What:    ast.IsFalse}},
		{`a IS UNKNOWN`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			What:    ast.IsUnknown}},
		{`a IS DISTINCT FROM b`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			What:    ast.IsDistinctFrom,
			Right:   &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
		{`a IS NOT DISTINCT FROM b`, &ast.IsPred{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Negated: true,
			What:    ast.IsDistinctFrom,
			Right:   &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
		{`a <> b`, &ast.Binary{Op: ast.Ne,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
		{`a <= b`, &ast.Binary{Op: ast.Le,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
		{`a >= b`, &ast.Binary{Op: ast.Ge,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
		{`a < b`, &ast.Binary{Op: ast.Lt,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
		{`a > b`, &ast.Binary{Op: ast.Gt,
			Left:  &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("predicate %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestBetweenReduction 简报指定的归约顺序:a NOT BETWEEN b AND c AND d
// → ((a NOT BETWEEN b AND c) AND d)(第一个 AND 归 BETWEEN,第二个归 AND 层)。
func TestBetweenReduction(t *testing.T) {
	got := mustExpr(t, "postgresql", `a NOT BETWEEN b AND c AND d`)
	want := &ast.Binary{Op: ast.And,
		Left: &ast.Between{
			Operand: &ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
			Low:     &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
			High:    &ast.Identifier{Parts: []ast.IdentPart{{Value: "c"}}},
			Negated: true},
		Right: &ast.Identifier{Parts: []ast.IdentPart{{Value: "d"}}}}
	if !reflect.DeepEqual(normAST(got), normAST(want)) {
		t.Fatalf("got %#v", got)
	}
}

// TestBangEqualConformance fix round 1:`!=` 按 conformance 档位决定接受
// 边界(对齐 Java comp() 的 NE2 分支 isBangEqualAllowed,控制者 jar 实测
// 复核)——Default 档(postgresql/trino)拒绝;MySQL5/Lenient(mysql/
// hive/sparksql)接受并归一为 Binary{Ne}。`<>` 在五方言均接受。
func TestBangEqualConformance(t *testing.T) {
	wantBang := &ast.Binary{Op: ast.Ne,
		Left:  &ast.Literal{Kind: ast.Int, Text: "1"},
		Right: &ast.Literal{Kind: ast.Int, Text: "2"}}
	for _, name := range []string{"mysql", "hive", "sparksql"} {
		got := mustExpr(t, name, `1 != 2`)
		if !reflect.DeepEqual(normAST(got), normAST(wantBang)) {
			t.Fatalf("bang equal %q (dialect %s): got %#v, want %#v", `1 != 2`, name, got, wantBang)
		}
	}
	for _, name := range []string{"postgresql", "trino"} {
		_, err := newTestParser(t, name).ParseExprStr(`1 != 2`)
		if err == nil {
			t.Fatalf("bang equal %q (dialect %s): expected PARSE_ERROR, got nil error", `1 != 2`, name)
		}
		var me *maskerr.Error
		if !errors.As(err, &me) || me.Code != maskerr.ParseError {
			t.Fatalf("bang equal %q (dialect %s): got %v, want PARSE_ERROR", `1 != 2`, name, err)
		}
		if want := "Bang equal '!=' is not allowed under the current SQL conformance level"; me.Message != "Parse error at Line 1, Column 3: "+want {
			t.Fatalf("bang equal %q (dialect %s): message = %q, want %q", `1 != 2`, name, me.Message, want)
		}
	}
	// <> 不受 conformance 影响,五方言均接受且同为 Binary{Ne}。
	for _, name := range []string{"postgresql", "trino", "mysql", "hive", "sparksql"} {
		got := mustExpr(t, name, `1 <> 2`)
		if !reflect.DeepEqual(normAST(got), normAST(wantBang)) {
			t.Fatalf("ne %q (dialect %s): got %#v, want %#v", `1 <> 2`, name, got, wantBang)
		}
	}
}

// TestWindow 内联 OVER 窗口:PARTITION BY / ORDER BY / ROWS|RANGE 帧,
// 含单边界形式(End 为零值 FrameBound,Task 5 交接约定)。
func TestWindow(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`f(x) OVER (PARTITION BY a ORDER BY b ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)`,
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "f"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}},
				Over: &ast.WindowSpec{
					PartitionBy: []ast.Expr{
						&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
					Order: []ast.OrderItem{
						{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}},
					Frame: &ast.Frame{
						Unit:  ast.Rows,
						Start: ast.FrameBound{Kind: ast.UnboundedPreceding},
						End:   ast.FrameBound{Kind: ast.CurrentRow}}}}},
		{`SUM(x) OVER (ORDER BY y DESC NULLS LAST ROWS UNBOUNDED PRECEDING)`,
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "sum"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}},
				Over: &ast.WindowSpec{
					Order: []ast.OrderItem{{
						Expr:       &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}},
						Dir:        ast.Desc,
						NullsFirst: newFalse()}},
					Frame: &ast.Frame{
						Unit:  ast.Rows,
						Start: ast.FrameBound{Kind: ast.UnboundedPreceding}}}}},
		{`COUNT(*) OVER ()`,
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "count"}}},
				Star: true,
				Over: &ast.WindowSpec{}}},
		{`AVG(x) OVER (ORDER BY y RANGE BETWEEN 1 PRECEDING AND 1 FOLLOWING)`,
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "avg"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}},
				Over: &ast.WindowSpec{
					Order: []ast.OrderItem{
						{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "y"}}}}},
					Frame: &ast.Frame{
						Unit:  ast.Range,
						Start: ast.FrameBound{Kind: ast.Preceding, Offset: &ast.Literal{Kind: ast.Int, Text: "1"}},
						End:   ast.FrameBound{Kind: ast.Following, Offset: &ast.Literal{Kind: ast.Int, Text: "1"}}}}}},
		{`SUM(a) OVER (PARTITION BY p, q)`,
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "sum"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
				Over: &ast.WindowSpec{
					PartitionBy: []ast.Expr{
						&ast.Identifier{Parts: []ast.IdentPart{{Value: "p"}}},
						&ast.Identifier{Parts: []ast.IdentPart{{Value: "q"}}}}}}},
		{`SUM(a) OVER (ORDER BY b ASC NULLS FIRST)`,
			&ast.FunctionCall{
				Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "sum"}}},
				Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}}},
				Over: &ast.WindowSpec{
					Order: []ast.OrderItem{{
						Expr:       &ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}},
						Dir:        ast.Asc,
						NullsFirst: newTrue()}}}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("window %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
	// 单边界帧缺边界、帧后残片均报错。
	wantExprError(t, "postgresql", `f(x) OVER (ROWS)`, 16)
	wantExprError(t, "postgresql", `f(x) OVER (ROWS BETWEEN UNBOUNDED PRECEDING)`, 44)
}

func newTrue() *bool  { b := true; return &b }
func newFalse() *bool { b := false; return &b }

// TestParseStatementLexicalErrorPassthrough:ParseStatement(Task 9 起为完整
// 入口)对词法错误原样透传(New 内部已调 lexer.Lex);正常语句可解析。
func TestParseStatementLexicalErrorPassthrough(t *testing.T) {
	prof, err := dialect.ByName("postgresql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = New(prof, "SELECT 1").ParseStatement(); err != nil {
		t.Fatalf("ParseStatement(SELECT 1): unexpected error: %v", err)
	}
	// 词法错误在 ParseStatement 中透传。
	_, err = New(prof, `'unterminated`).ParseStatement()
	var me *maskerr.Error
	if !errors.As(err, &me) || me.Code != maskerr.ParseError || !strings.Contains(err.Error(), "Lexical error") {
		t.Fatalf("ParseStatement with lexical error: got %v", err)
	}
}

// TestParseExprRejects 本任务不支持的构造遇之即报 PARSE_ERROR,且 message
// 含 Line/Column 位置(决策 5;GROUP BY 语境的 ROLLUP/CUBE 构造在 T8 拒绝)。
func TestParseExprRejects(t *testing.T) {
	tests := []struct {
		src string
		col int
	}{
		{`a::b`, 2},                  // :: 强转
		{`f(x) FILTER (WHERE y)`, 6}, // FILTER (WHERE ...)
		{`X'ff'`, 1},                 // 二进制字面量(紧邻 Ident X + String)
		{`UNNEST(a)`, 1},             // UNNEST 表函数
		{`TABLESAMPLE(t)`, 1},        // TABLESAMPLE 表函数
		{`CAST(x AS FOO)`, 11},       // 清单外类型名
		{`a RLIKE b`, 3},             // RLIKE 不在 M1 谓词面
		{`a -> b`, 4},                // -> 非 Calcite 标准算子(- 后遇 > 报错)
		{`ROLLUP (a)`, 1},            // ROLLUP 保留字,表达式语境即拒(fix round 2)
		{`rollup(a)`, 1},             // 同上,小写同拒
		{`LATERAL (a)`, 1},           // LATERAL 保留字(决策 5),表达式语境即拒
		{`GROUPING SETS (a)`, 10},    // 两词形式:GROUPING 成标识符后 SETS 为残片
		{`1 2`, 3},                   // 表达式后残片
		{`a b`, 3},                   // 同上
		{``, 1},                      // 空输入
		{`ROWS 1`, 6},                // 窗口帧语法裸露在表达式外(ROWS 仅是普通词)
	}
	for _, tc := range tests {
		wantExprError(t, "postgresql", tc.src, tc.col)
	}
	// ROLLUP 的错误文案对齐 Java(jar 实测 "Incorrect syntax near the
	// keyword 'ROLLUP'"),经 errAt 前缀后含位置。
	_, err := newTestParser(t, "postgresql").ParseExprStr(`ROLLUP (a)`)
	var me *maskerr.Error
	if !errors.As(err, &me) || !strings.Contains(me.Message, "Incorrect syntax near the keyword 'ROLLUP'") {
		t.Fatalf("ROLLUP message = %v, want \"Incorrect syntax near the keyword 'ROLLUP'\"", err)
	}
	// X'..' 与普通「标识符 + 字符串」(中间有空白)的区分:后者在表达式
	// 上下文因残片报错,位置落在字符串上而非标识符上。
	wantExprError(t, "postgresql", `x 'y'`, 3)
}

// TestGroupingSetsFamilyExpressionContext fix round 2:Java 实测(jar)边界——
// CUBE/GROUPING/SETS 单词形式在表达式语境解析接受(校验期才失败),故按
// 普通函数调用放行;ROLLUP/GROUPING SETS/LATERAL 拒绝见 TestParseExprRejects。
func TestGroupingSetsFamilyExpressionContext(t *testing.T) {
	tests := []struct {
		src  string
		want ast.Expr
	}{
		{`CUBE(x)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "cube"}}},
			Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}}}},
		{`GROUPING(a, b)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "grouping"}}},
			Args: []ast.Expr{
				&ast.Identifier{Parts: []ast.IdentPart{{Value: "a"}}},
				&ast.Identifier{Parts: []ast.IdentPart{{Value: "b"}}}}}},
		{`SETS(1)`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "sets"}}},
			Args: []ast.Expr{&ast.Literal{Kind: ast.Int, Text: "1"}}}},
		{`cube(x) OVER ()`, &ast.FunctionCall{
			Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "cube"}}},
			Args: []ast.Expr{&ast.Identifier{Parts: []ast.IdentPart{{Value: "x"}}}},
			Over: &ast.WindowSpec{}}},
	}
	for _, tc := range tests {
		got := mustExpr(t, "postgresql", tc.src)
		if !reflect.DeepEqual(normAST(got), normAST(tc.want)) {
			t.Fatalf("expr %q: got %#v, want %#v", tc.src, got, tc.want)
		}
	}
}

// TestLexErrorViaParseExpr New 内部完成词法分析,词法错误经 ParseExpr 透传。
func TestLexErrorViaParseExpr(t *testing.T) {
	prof, err := dialect.ByName("postgresql")
	if err != nil {
		t.Fatal(err)
	}
	_, err = New(prof, `'abc`).ParseExpr()
	var me *maskerr.Error
	if !errors.As(err, &me) || me.Code != maskerr.ParseError || !strings.Contains(err.Error(), "Lexical error") {
		t.Fatalf("ParseExpr with lexical error: got %v", err)
	}
}
