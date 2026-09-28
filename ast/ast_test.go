package ast

import (
	"testing"

	"io.sqlmask/go/lexer"
)

// 编译期接口满足性断言:全节点覆盖(Statement 八型、Query 五型、
// TableRef 三型、Expr 十五型;WindowSpec/TypeSpec 携带 Pos 故须满足 Node)。
var (
	_ Statement = (*Select)(nil)
	_ Statement = (*Values)(nil)
	_ Statement = (*SetOp)(nil)
	_ Statement = (*With)(nil)
	_ Statement = (*OrderBy)(nil)
	_ Statement = (*Insert)(nil)
	_ Statement = (*InsertOverwrite)(nil)
	_ Statement = (*CreateTable)(nil)

	_ Query = (*Select)(nil)
	_ Query = (*Values)(nil)
	_ Query = (*SetOp)(nil)
	_ Query = (*With)(nil)
	_ Query = (*OrderBy)(nil)

	_ TableRef = (*TableNameRef)(nil)
	_ TableRef = (*DerivedTable)(nil)
	_ TableRef = (*Join)(nil)

	_ Expr = (*Identifier)(nil)
	_ Expr = (*Literal)(nil)
	_ Expr = (*Param)(nil)
	_ Expr = (*Unary)(nil)
	_ Expr = (*Binary)(nil)
	_ Expr = (*IsPred)(nil)
	_ Expr = (*Between)(nil)
	_ Expr = (*InPred)(nil)
	_ Expr = (*LikePred)(nil)
	_ Expr = (*Exists)(nil)
	_ Expr = (*Subquery)(nil)
	_ Expr = (*FunctionCall)(nil)
	_ Expr = (*Case)(nil)
	_ Expr = (*Cast)(nil)
	_ Expr = (*Collate)(nil)

	_ Node = (*WindowSpec)(nil)
	_ Node = (*TypeSpec)(nil)
)

func TestPosReturnsOwnField(t *testing.T) {
	p := lexer.Pos{Line: 3, Column: 7}
	nodes := []struct {
		name string
		node Node
	}{
		{"Select", &Select{Pos: p}},
		{"Values", &Values{Pos: p}},
		{"SetOp", &SetOp{Pos: p}},
		{"With", &With{Pos: p}},
		{"OrderBy", &OrderBy{Pos: p}},
		{"Insert", &Insert{Pos: p}},
		{"InsertOverwrite", &InsertOverwrite{Pos: p}},
		{"CreateTable", &CreateTable{Pos: p}},
		{"TableNameRef", &TableNameRef{Pos: p}},
		{"DerivedTable", &DerivedTable{Pos: p}},
		{"Join", &Join{Pos: p}},
		{"Identifier", &Identifier{Pos: p}},
		{"Literal", &Literal{Pos: p}},
		{"Param", &Param{Pos: p}},
		{"Unary", &Unary{Pos: p}},
		{"Binary", &Binary{Pos: p}},
		{"IsPred", &IsPred{Pos: p}},
		{"Between", &Between{Pos: p}},
		{"InPred", &InPred{Pos: p}},
		{"LikePred", &LikePred{Pos: p}},
		{"Exists", &Exists{Pos: p}},
		{"Subquery", &Subquery{Pos: p}},
		{"FunctionCall", &FunctionCall{Pos: p}},
		{"Case", &Case{Pos: p}},
		{"Cast", &Cast{Pos: p}},
		{"Collate", &Collate{Pos: p}},
		{"WindowSpec", &WindowSpec{Pos: p}},
		{"TypeSpec", &TypeSpec{Pos: p}},
	}
	for _, tc := range nodes {
		// 简报 Step 1 的 "Pos() 返回字段值":访问器落地名 Position()(与
		// 字段 Pos 同名冲突,见包注释裁定),语义不变 = 返回自身 Pos 字段。
		if got := tc.node.Position(); got != p {
			t.Errorf("%s.Position() = %v, want %v", tc.name, got, p)
		}
	}
}

func TestEnumString(t *testing.T) {
	tests := []struct {
		got  string
		want string
	}{
		// OrderDir
		{Unspecified.String(), "Unspecified"},
		{Asc.String(), "Asc"},
		{Desc.String(), "Desc"},
		// SetOpKind
		{Union.String(), "Union"},
		{Intersect.String(), "Intersect"},
		{Except.String(), "Except"},
		// JoinKind
		{Inner.String(), "Inner"},
		{Left.String(), "Left"},
		{Right.String(), "Right"},
		{Full.String(), "Full"},
		{Cross.String(), "Cross"},
		{Comma.String(), "Comma"},
		// CreateTableVariant
		{Plain.String(), "Plain"},
		{Replace.String(), "Replace"},
		{Volatile.String(), "Volatile"},
		{Set.String(), "Set"},
		{Multiset.String(), "Multiset"},
		// LiteralKind
		{Null.String(), "Null"},
		{True.String(), "True"},
		{False.String(), "False"},
		{Int.String(), "Int"},
		{Decimal.String(), "Decimal"},
		{Approx.String(), "Approx"},
		{String.String(), "String"},
		{Date.String(), "Date"},
		{Time.String(), "Time"},
		{Timestamp.String(), "Timestamp"},
		{Interval.String(), "Interval"},
		// UnaryOp
		{Neg.String(), "Neg"},
		{Plus.String(), "Plus"},
		{Not.String(), "Not"},
		// BinaryOp
		{Or.String(), "Or"},
		{And.String(), "And"},
		{Eq.String(), "Eq"},
		{Ne.String(), "Ne"},
		{Lt.String(), "Lt"},
		{Le.String(), "Le"},
		{Gt.String(), "Gt"},
		{Ge.String(), "Ge"},
		{Add.String(), "Add"},
		{Sub.String(), "Sub"},
		{Concat.String(), "Concat"},
		{Mul.String(), "Mul"},
		{Div.String(), "Div"},
		{Mod.String(), "Mod"},
		// IsWhat(Is 前缀裁定,见包注释)
		{IsNull.String(), "IsNull"},
		{IsTrue.String(), "IsTrue"},
		{IsFalse.String(), "IsFalse"},
		{IsUnknown.String(), "IsUnknown"},
		{IsDistinctFrom.String(), "IsDistinctFrom"},
		// LikeKind
		{Like.String(), "Like"},
		{ILike.String(), "ILike"},
		{Similar.String(), "Similar"},
		// FrameUnit
		{Rows.String(), "Rows"},
		{Range.String(), "Range"},
		// BoundKind
		{UnboundedPreceding.String(), "UnboundedPreceding"},
		{Preceding.String(), "Preceding"},
		{CurrentRow.String(), "CurrentRow"},
		{Following.String(), "Following"},
		{UnboundedFollowing.String(), "UnboundedFollowing"},
		// 越界回退
		{SetOpKind(99).String(), "SetOpKind(99)"},
		{OrderDir(-1).String(), "OrderDir(-1)"},
		{LiteralKind(42).String(), "LiteralKind(42)"},
	}
	for _, tc := range tests {
		if tc.got != tc.want {
			t.Errorf("enum String: got %q, want %q", tc.got, tc.want)
		}
	}
}

// TestCompositionSmoke 纯编译性冒烟:典型 M1 形态可按简报字段完整构造,
// 且查询体五节点可同时作为 Statement 与 Query 使用。
func TestCompositionSmoke(t *testing.T) {
	f := false
	star := &Identifier{Parts: []IdentPart{{Value: "star"}}}
	query := &Select{
		Pos: lexer.Pos{Line: 1, Column: 1},
		Items: []SelectItem{
			{Expr: &Literal{Kind: Int, Text: "1"}},
			{Star: true},
			{Expr: star, Alias: &Identifier{Parts: []IdentPart{{Value: "s"}, {Value: "x", Quoted: true}}}},
		},
		From: []TableRef{
			&Join{
				Kind: Inner,
				Left: &TableNameRef{
					Parts: []Identifier{
						{Parts: []IdentPart{{Value: "db"}}},
						{Parts: []IdentPart{{Value: "t1"}}},
					},
					Alias: &TableAlias{Name: Identifier{Parts: []IdentPart{{Value: "a"}}}, Columns: []Identifier{{Parts: []IdentPart{{Value: "c1"}}}}},
				},
				Right: &DerivedTable{
					Query: &Values{Rows: [][]Expr{{&Literal{Kind: Null}, &Param{Index: -1}}}},
					Alias: &TableAlias{Name: Identifier{Parts: []IdentPart{{Value: "d"}}}},
				},
				On:    &Binary{Op: Eq, Left: star, Right: star},
				Using: []Identifier{{Parts: []IdentPart{{Value: "id"}}}},
			},
		},
		Where: &Between{
			Operand:   star,
			Low:       &Literal{Kind: Decimal, Text: "1.5"},
			High:      &Unary{Op: Neg, Operand: &Literal{Kind: Approx, Text: "2e10"}},
			Symmetric: true,
		},
		GroupBy: []Expr{star},
		Having:  &IsPred{Operand: star, Negated: true, What: IsNull},
		Window:  []WindowDef{{PartitionBy: []Expr{star}, Order: []OrderItem{{Expr: star, Dir: Desc}}, Frame: &Frame{Unit: Rows, Start: FrameBound{Kind: UnboundedPreceding}, End: FrameBound{Kind: CurrentRow}}}},
	}
	var stmt Statement = &OrderBy{
		Pos:    lexer.Pos{Line: 2, Column: 3},
		Query:  query,
		Items:  []OrderItem{{Expr: star, Dir: Asc, NullsFirst: &f}},
		Limit:  &Literal{Kind: Int, Text: "10"},
		Offset: &Param{Index: 1},
		Fetch:  &FunctionCall{Name: Identifier{Parts: []IdentPart{{Value: "row_number"}}}, Over: &WindowSpec{PartitionBy: []Expr{star}}},
	}
	var _ Statement = &Values{Rows: [][]Expr{{&Literal{Kind: True}, &Literal{Kind: False}}}}
	var _ Statement = &SetOp{Op: Union, All: true, Left: query, Right: &Values{}}
	var _ Statement = &With{
		Recursive: true,
		Items:     []WithItem{{Name: Identifier{Parts: []IdentPart{{Value: "w"}}}, Columns: []Identifier{{Parts: []IdentPart{{Value: "c"}}}}, Body: query}},
		Body:      query,
	}
	var _ Statement = &Insert{Target: TableNameRef{Parts: []Identifier{{Parts: []IdentPart{{Value: "t"}}}}}, Columns: []Identifier{{Parts: []IdentPart{{Value: "c"}}}}, Source: query}
	var _ Statement = &InsertOverwrite{Target: TableNameRef{}, Source: query}
	var _ Statement = &CreateTable{Variant: Replace, IfNotExists: true, Name: TableNameRef{}, Columns: []Identifier{{}}, Query: query}
	var _ Statement = &CreateTable{Variant: Multiset, Name: TableNameRef{}, Query: query}
	var _ Statement = &CreateTable{Variant: Volatile, Name: TableNameRef{}, Query: query}
	var _ Statement = &CreateTable{Variant: Set, Name: TableNameRef{}, Query: query}

	_ = &Case{Operand: star, Whens: []When{{Cond: star, Then: star}}, Else: star}
	_ = &Cast{Operand: star, Type: TypeSpec{Name: "DECIMAL", Precision: &Literal{Kind: Int, Text: "10"}, Scale: &Literal{Kind: Int, Text: "2"}}}
	_ = &Collate{Operand: star, Collation: Identifier{Parts: []IdentPart{{Value: "ci"}}}}
	_ = &LikePred{Operand: star, Pattern: &Literal{Kind: String, Text: "'x'"}, Kind: ILike, Escape: &Literal{Kind: String, Text: "'!'"}}
	_ = &LikePred{Operand: star, Pattern: star, Kind: Similar}
	_ = &InPred{Operand: star, Negated: true, List: []Expr{star}}
	_ = &InPred{Operand: star, Subquery: &Subquery{Query: query}}
	_ = &Exists{Subquery: &Subquery{Query: query}}
	_ = &Binary{Op: Concat, Left: star, Right: star}
	_ = &IsPred{Operand: star, What: IsDistinctFrom, Right: star}
	_ = &IsPred{Operand: star, What: IsTrue}
	_ = &IsPred{Operand: star, Negated: true, What: IsFalse}
	_ = &IsPred{Operand: star, What: IsUnknown}
	_ = &Unary{Op: Plus, Operand: star}
	_ = &Unary{Op: Not, Operand: star}
	_ = &Literal{Kind: Interval, Text: "'1' DAY", Interval: IntervalLit{Text: "1", StartUnit: "DAY", EndUnit: "HOUR"}}
	_ = &Literal{Kind: Date, Text: "DATE '2026-01-01'"}
	_ = &Literal{Kind: Time, Text: "TIME '00:00'"}
	_ = &Literal{Kind: Timestamp, Text: "TIMESTAMP '2026-01-01 00:00'"}
	_ = &SelectItem{Star: true, StarQualifier: []IdentPart{{Value: "t"}}}
	_ = &Frame{Unit: Range, Start: FrameBound{Kind: Preceding, Offset: star}, End: FrameBound{Kind: Following, Offset: star}}
	_ = &FrameBound{Kind: UnboundedFollowing}
	_ = &TableAlias{Name: Identifier{Parts: []IdentPart{{Value: "x"}}}}
	_ = &IdentPart{Value: "q", Quoted: true}
	_ = stmt
}
