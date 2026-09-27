package parser

import (
	"reflect"
	"testing"

	"io.sqlmask/go/ast"
)

// 本文件为 Task 8(集合运算、ORDER BY/LIMIT/OFFSET/FETCH 限尾、TOP(n) 拒绝)
// 的测试:沿用 testutil_test.go / select_test.go 的范式(newTestParser +
// ParseQueryStr + normAST 清零 Pos 后 DeepEqual),期望 AST 全部字面构造。
//
// 语法口径(镜像 fork mask-sqlparser Parser.jj 的可观测文法):
//   - 集合运算:UNION/EXCEPT 同级左结合,INTERSECT 优先级更高
//     (Calcite SqlParserUtil.toTree 优先级攀爬);量词 ALL/DISTINCT 可选;
//     括号查询作操作数直接取括号内 Query,不产生额外包装节点;
//   - 限尾(fork OrderByLimitOpt):ORDER BY e [ASC|DESC] [NULLS FIRST|LAST]
//     与 LIMIT n|ALL / OFFSET n [ROW|ROWS] / FETCH FIRST|NEXT n [ROW|ROWS] ONLY;
//     LIMIT [OFFSET] 与 OFFSET [FETCH] 两分支合法,LIMIT 与 FETCH 互斥
//     (Calcite 同一 Fetch 产生式的两个分支,不可同现);
//   - LIMIT ALL 镜像 Calcite 归约为精确数值 -1(OrderBy.Fetch/Limit 承载);
//   - TOP (n) / TOP n:fork SqlMaskTopN 挂点,五方言 AllowTopN=false →
//     解析期 PARSE_ERROR(message 含 TOP);TOP 后不随 '(' 或数字时仍为
//     决策 4 非保留标识符(选择项/别名不受影响)。

// fiveDialects 全部注册方言(错误信息引用顺序 = registry 声明序)。
var fiveDialects = []string{"postgresql", "trino", "mysql", "hive", "sparksql"}

// intLit 构造整数字面量(测试字面构造辅助)。
func intLit(text string) *ast.Literal {
	return &ast.Literal{Kind: ast.Int, Text: text}
}

// intSel 构造单选择项裸 SELECT(期望 AST 辅助)。
func intSel(text string) *ast.Select {
	return &ast.Select{Items: []ast.SelectItem{{Expr: intLit(text)}}}
}

// boolPtr 构造 NullsFirst 指针。
func boolPtr(b bool) *bool { return &b }

// mustQueryAllDialects 对五个方言逐一解析并断言得到同一期望 AST
// (集合运算/限尾为方言无关语法面)。
func mustQueryAllDialects(t *testing.T, src string, want ast.Query) {
	t.Helper()
	for _, d := range fiveDialects {
		got := mustQuery(t, d, src)
		if !reflect.DeepEqual(normAST(got), normAST(want)) {
			t.Fatalf("ParseQueryStr(%q) with dialect %s:\n got  %#v\n want %#v", src, d, got, want)
		}
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:集合运算
// ---------------------------------------------------------------------------

func TestSetOp(t *testing.T) {
	tests := []struct {
		name string
		src  string
		want ast.Query
	}{
		{
			// 简报:`a UNION ALL b UNION c` 左结合树
			name: "union all left-assoc",
			src:  `SELECT 1 UNION ALL SELECT 2 UNION SELECT 3`,
			want: &ast.SetOp{
				Op:    ast.Union,
				All:   false,
				Left:  &ast.SetOp{Op: ast.Union, All: true, Left: intSel("1"), Right: intSel("2")},
				Right: intSel("3"),
			},
		},
		{
			// 同级左结合(无 ALL):((1 UNION 2) UNION 3)
			name: "union left-assoc",
			src:  `SELECT 1 UNION SELECT 2 UNION SELECT 3`,
			want: &ast.SetOp{
				Op:    ast.Union,
				Left:  &ast.SetOp{Op: ast.Union, Left: intSel("1"), Right: intSel("2")},
				Right: intSel("3"),
			},
		},
		{
			// 简报:`a INTERSECT b UNION c` 先 intersect
			name: "intersect binds tighter",
			src:  `SELECT 1 INTERSECT SELECT 2 UNION SELECT 3`,
			want: &ast.SetOp{
				Op:    ast.Union,
				Left:  &ast.SetOp{Op: ast.Intersect, Left: intSel("1"), Right: intSel("2")},
				Right: intSel("3"),
			},
		},
		{
			// INTERSECT 在 EXCEPT 右操作数上同样先归约
			name: "except with intersect right",
			src:  `SELECT 1 EXCEPT SELECT 2 INTERSECT SELECT 3`,
			want: &ast.SetOp{
				Op:    ast.Except,
				Left:  intSel("1"),
				Right: &ast.SetOp{Op: ast.Intersect, Left: intSel("2"), Right: intSel("3")},
			},
		},
		{
			// INTERSECT 链同级左结合
			name: "intersect left-assoc",
			src:  `SELECT 1 INTERSECT SELECT 2 INTERSECT SELECT 3`,
			want: &ast.SetOp{
				Op:    ast.Intersect,
				Left:  &ast.SetOp{Op: ast.Intersect, Left: intSel("1"), Right: intSel("2")},
				Right: intSel("3"),
			},
		},
		{
			// 括号改变结合:1 UNION (2 UNION 3) —— 括号内 Query 直接挂上
			name: "paren right operand",
			src:  `SELECT 1 UNION (SELECT 2 UNION SELECT 3)`,
			want: &ast.SetOp{
				Op:    ast.Union,
				Left:  intSel("1"),
				Right: &ast.SetOp{Op: ast.Union, Left: intSel("2"), Right: intSel("3")},
			},
		},
		{
			// 简报:`(SELECT 1) UNION (SELECT 2)`:括号操作数不产生包装节点
			name: "paren both operands",
			src:  `(SELECT 1) UNION (SELECT 2)`,
			want: &ast.SetOp{Op: ast.Union, Left: intSel("1"), Right: intSel("2")},
		},
		{
			// 括号内集合运算:整个括号查询即 SetOp
			name: "paren contains setop",
			src:  `(SELECT 1 UNION SELECT 2)`,
			want: &ast.SetOp{Op: ast.Union, Left: intSel("1"), Right: intSel("2")},
		},
		{
			// UNION DISTINCT:显式 DISTINCT 与缺省一致(All=false)
			name: "union distinct",
			src:  `SELECT 1 UNION DISTINCT SELECT 2`,
			want: &ast.SetOp{Op: ast.Union, All: false, Left: intSel("1"), Right: intSel("2")},
		},
		{
			name: "except all",
			src:  `SELECT 1 EXCEPT ALL SELECT 2`,
			want: &ast.SetOp{Op: ast.Except, All: true, Left: intSel("1"), Right: intSel("2")},
		},
		{
			name: "intersect all",
			src:  `SELECT 1 INTERSECT ALL SELECT 2`,
			want: &ast.SetOp{Op: ast.Intersect, All: true, Left: intSel("1"), Right: intSel("2")},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			mustQueryAllDialects(t, tc.src, tc.want)
		})
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:ORDER BY / LIMIT / OFFSET / FETCH 限尾
// ---------------------------------------------------------------------------

func TestOrderAndTail(t *testing.T) {
	tests := []struct {
		name string
		src  string
		want ast.Query
	}{
		{
			// 简报:`ORDER BY 1 DESC NULLS LAST`;OrderBy 包装 Query=*Select
			name: "order by desc nulls last",
			src:  `SELECT 1 ORDER BY 1 DESC NULLS LAST`,
			want: &ast.OrderBy{
				Query: intSel("1"),
				Items: []ast.OrderItem{{Expr: intLit("1"), Dir: ast.Desc, NullsFirst: boolPtr(false)}},
			},
		},
		{
			name: "order by asc nulls first",
			src:  `SELECT 1 ORDER BY 1 ASC NULLS FIRST`,
			want: &ast.OrderBy{
				Query: intSel("1"),
				Items: []ast.OrderItem{{Expr: intLit("1"), Dir: ast.Asc, NullsFirst: boolPtr(true)}},
			},
		},
		{
			// 无 ASC/DESC/NULLS:Dir=Unspecified,NullsFirst=nil
			name: "order by bare item",
			src:  `SELECT 1 ORDER BY 1`,
			want: &ast.OrderBy{
				Query: intSel("1"),
				Items: []ast.OrderItem{{Expr: intLit("1")}},
			},
		},
		{
			// 多排序项
			name: "order by two items",
			src:  `SELECT 1 ORDER BY 1, 2 DESC`,
			want: &ast.OrderBy{
				Query: intSel("1"),
				Items: []ast.OrderItem{
					{Expr: intLit("1")},
					{Expr: intLit("2"), Dir: ast.Desc},
				},
			},
		},
		{
			// ORDER BY 包装整个集合运算
			name: "order by wraps setop",
			src:  `SELECT 1 UNION SELECT 2 ORDER BY 1`,
			want: &ast.OrderBy{
				Query: &ast.SetOp{Op: ast.Union, Left: intSel("1"), Right: intSel("2")},
				Items: []ast.OrderItem{{Expr: intLit("1")}},
			},
		},
		{
			// 简报:`LIMIT 5`
			name: "limit",
			src:  `SELECT 1 LIMIT 5`,
			want: &ast.OrderBy{Query: intSel("1"), Limit: intLit("5")},
		},
		{
			// 简报:`LIMIT ALL OFFSET 3 ROWS`;LIMIT ALL 归约 -1(镜像 Calcite)
			name: "limit all offset rows",
			src:  `SELECT 1 LIMIT ALL OFFSET 3 ROWS`,
			want: &ast.OrderBy{Query: intSel("1"), Limit: intLit("-1"), Offset: intLit("3")},
		},
		{
			// OFFSET 后 ROW|ROWS 可选(fork OffsetClause 可选计量词)
			name: "limit all offset bare",
			src:  `SELECT 1 LIMIT ALL OFFSET 3`,
			want: &ast.OrderBy{Query: intSel("1"), Limit: intLit("-1"), Offset: intLit("3")},
		},
		{
			name: "offset rows alone",
			src:  `SELECT 1 OFFSET 2 ROWS`,
			want: &ast.OrderBy{Query: intSel("1"), Offset: intLit("2")},
		},
		{
			// 简报:`OFFSET n FETCH..` 合法;NEXT 形态
			name: "offset fetch next",
			src:  `SELECT 1 OFFSET 2 ROWS FETCH NEXT 3 ROWS ONLY`,
			want: &ast.OrderBy{Query: intSel("1"), Offset: intLit("2"), Fetch: intLit("3")},
		},
		{
			// 简报:`FETCH FIRST 10 ROWS ONLY`
			name: "fetch first only",
			src:  `SELECT 1 FETCH FIRST 10 ROWS ONLY`,
			want: &ast.OrderBy{Query: intSel("1"), Fetch: intLit("10")},
		},
		{
			// 量值为动态参数(fork UnsignedNumericLiteralOrParam)
			name: "limit param",
			src:  `SELECT 1 LIMIT ?`,
			want: &ast.OrderBy{Query: intSel("1"), Limit: &ast.Param{Index: -1}},
		},
		{
			// 限尾作用于整个集合运算(LIMIT 与 ORDER BY 同现)
			name: "setop order limit",
			src:  `SELECT 1 UNION SELECT 2 ORDER BY 1 LIMIT 2`,
			want: &ast.OrderBy{
				Query: &ast.SetOp{Op: ast.Union, Left: intSel("1"), Right: intSel("2")},
				Items: []ast.OrderItem{{Expr: intLit("1")}},
				Limit: intLit("2"),
			},
		},
		{
			// 括号内限尾留在括号内(派生表):OrderBy 包装在 DerivedTable.Query
			name: "derived table inner tail",
			src:  `SELECT * FROM (SELECT 1 ORDER BY 1 LIMIT 1) t`,
			want: &ast.Select{
				Items: []ast.SelectItem{{Star: true}},
				From: []ast.TableRef{&ast.DerivedTable{
					Query: &ast.OrderBy{
						Query: intSel("1"),
						Items: []ast.OrderItem{{Expr: intLit("1")}},
						Limit: intLit("1"),
					},
					Alias: &ast.TableAlias{Name: ast.Identifier{Parts: []ast.IdentPart{{Value: "t"}}}},
				}},
			},
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			mustQueryAllDialects(t, tc.src, tc.want)
		})
	}
}

// TestOrderByWrapperType 简报:ORDER BY 包装节点类型断言(OrderBy{Query: *Select})。
func TestOrderByWrapperType(t *testing.T) {
	got := mustQuery(t, "postgresql", `SELECT 1 ORDER BY 1`)
	ob, ok := got.(*ast.OrderBy)
	if !ok {
		t.Fatalf("want *ast.OrderBy wrapper, got %T", got)
	}
	if _, ok := ob.Query.(*ast.Select); !ok {
		t.Fatalf("OrderBy.Query want *ast.Select, got %T", ob.Query)
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:限尾拒绝(LIMIT 与 FETCH 互斥等)
// ---------------------------------------------------------------------------

func TestTailErrors(t *testing.T) {
	tests := []struct {
		name    string
		src     string
		wantCol int
		text    string
	}{
		{
			// 简报:`SELECT .. LIMIT 1 FETCH FIRST 1 ROWS ONLY` → PARSE_ERROR
			// (Calcite 语法中 LIMIT 与 FETCH 互斥)
			name:    "limit then fetch",
			src:     `SELECT 1 LIMIT 1 FETCH FIRST 1 ROWS ONLY`,
			wantCol: 18,
			text:    "FETCH",
		},
		{
			// LIMIT n OFFSET m FETCH 仍属 LIMIT+FETCH 互斥
			name:    "limit offset then fetch",
			src:     `SELECT 1 LIMIT 1 OFFSET 1 FETCH FIRST 1 ROWS ONLY`,
			wantCol: 27,
			text:    "FETCH",
		},
		{
			name:    "limit all then fetch",
			src:     `SELECT 1 LIMIT ALL FETCH FIRST 1 ROWS ONLY`,
			wantCol: 20,
			text:    "FETCH",
		},
		{
			// ORDER 缺 BY
			name:    "order without by",
			src:     `SELECT 1 ORDER 1`,
			wantCol: 16,
			text:    "BY",
		},
		{
			// NULLS 缺 FIRST/LAST
			name:    "nulls without first last",
			src:     `SELECT 1 ORDER BY 1 NULLS x`,
			wantCol: 27,
			text:    "FIRST",
		},
		{
			// FETCH 缺 ONLY
			name:    "fetch without only",
			src:     `SELECT 1 FETCH FIRST 1 ROWS WHERE x`,
			wantCol: 29,
			text:    "ONLY",
		},
		{
			// UNION 缺右操作数
			name:    "union without operand",
			src:     `SELECT 1 UNION FROM t`,
			wantCol: 16,
			text:    "SELECT",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			wantQueryErrorText(t, "postgresql", tc.src, tc.wantCol, tc.text)
		})
	}
}

// ---------------------------------------------------------------------------
// Step 1 清单:TOP(n) 五方言拒绝
// ---------------------------------------------------------------------------

func TestTopNRejected(t *testing.T) {
	// 简报:`SELECT TOP (5) ..` → PARSE_ERROR(五方言逐个断言,message 含 TOP)
	for _, d := range fiveDialects {
		wantQueryErrorText(t, d, `SELECT TOP (5) 1 FROM t`, 8, "TOP")
		wantQueryErrorText(t, d, `SELECT TOP 5 1 FROM t`, 8, "TOP")
	}
	// DISTINCT/ALL 之后的 TOP 挂点同样拒绝
	wantQueryErrorText(t, "postgresql", `SELECT DISTINCT TOP (5) 1 FROM t`, 17, "TOP")
	wantQueryErrorText(t, "hive", `SELECT ALL TOP 5 1 FROM t`, 12, "TOP")
	// PERCENT 形态在 conformance 检查后(fork 顺序)同样失败,message 含 TOP
	wantQueryErrorText(t, "postgresql", `SELECT TOP 10 PERCENT 1 FROM t`, 8, "TOP")
	// TOP 后随 '(' 即进入挂点:即使写成函数形态 top(5) 亦按 fork 拒绝
	wantQueryErrorText(t, "postgresql", `SELECT top(5) FROM t`, 8, "TOP")

	// TOP 不随 '(' 或数字时仍为决策 4 非保留标识符:列别名/表别名/标识符不受影响
	mustQueryAllDialects(t, `SELECT 1 top`, &ast.Select{
		Items: []ast.SelectItem{{Expr: intLit("1"),
			Alias: &ast.Identifier{Parts: []ast.IdentPart{{Value: "top"}}}}},
	})
	mustQueryAllDialects(t, `SELECT top FROM t`, &ast.Select{
		Items: []ast.SelectItem{{Expr: &ast.Identifier{Parts: []ast.IdentPart{{Value: "top"}}}}},
		From:  []ast.TableRef{tn1("t")},
	})
}
