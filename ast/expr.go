package ast

import (
	"strconv"

	"io.sqlmask/go/lexer"
)

// Expr 表达式节点公共接口。
type Expr interface {
	Node
	isExpr()
}

// ---------------------------------------------------------------------------
// 表达式
// ---------------------------------------------------------------------------

// Identifier 多段标识符(如 a.b.c);大小写折算由 parser 按 dialect.Profile
// 做,本包保存折算后的段文本。
type Identifier struct {
	Pos   lexer.Pos
	Parts []IdentPart
}

// IdentPart 单段标识符:文本与是否引号标识符。
type IdentPart struct {
	Value  string
	Quoted bool
}

// LiteralKind 字面量种别。
type LiteralKind int

const (
	Null LiteralKind = iota
	True
	False
	Int
	Decimal
	Approx
	String
	Date
	Time
	Timestamp
	Interval
)

var literalKindNames = [...]string{
	"Null", "True", "False", "Int", "Decimal", "Approx",
	"String", "Date", "Time", "Timestamp", "Interval",
}

// String 返回种别名,用于测试失败信息与报错可读。
func (k LiteralKind) String() string {
	if 0 <= int(k) && int(k) < len(literalKindNames) {
		return literalKindNames[k]
	}
	return "LiteralKind(" + strconv.Itoa(int(k)) + ")"
}

// Literal 字面量;Text 为源文片段(含引号原文),Interval 仅 Interval 种别使用。
type Literal struct {
	Pos      lexer.Pos
	Kind     LiteralKind
	Text     string
	Interval IntervalLit
}

// IntervalLit INTERVAL 字面量内容:正文与可选起止单位
// (如 INTERVAL '1' DAY TO HOUR → StartUnit "DAY"、EndUnit "HOUR")。
type IntervalLit struct {
	Text      string
	StartUnit string
	EndUnit   string
}

// Param 动态参数:? 为匿名参数(Index=-1,Name 空);?N 为 Index=N;
// 命名参数置 Name。
type Param struct {
	Pos   lexer.Pos
	Index int
	Name  string
}

// UnaryOp 一元运算种别。
type UnaryOp int

const (
	Neg UnaryOp = iota
	Plus
	Not
)

var unaryOpNames = [...]string{"Neg", "Plus", "Not"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k UnaryOp) String() string {
	if 0 <= int(k) && int(k) < len(unaryOpNames) {
		return unaryOpNames[k]
	}
	return "UnaryOp(" + strconv.Itoa(int(k)) + ")"
}

// Unary 一元运算(-x/+x/NOT x)。
type Unary struct {
	Pos     lexer.Pos
	Op      UnaryOp
	Operand Expr
}

// BinaryOp 二元运算种别。
type BinaryOp int

const (
	Or BinaryOp = iota
	And
	Eq
	Ne
	Lt
	Le
	Gt
	Ge
	Add
	Sub
	Concat
	Mul
	Div
	Mod
	// T11 追加(jar 实测 fork 解析接受的运算符;追加在枚举尾部,不扰动
	// 既有序):NullSafeEq 对应 <=>,BitAnd/BitXor/Tilde 对应 & ^ ~。
	NullSafeEq
	BitAnd
	BitXor
	Tilde
)

var binaryOpNames = [...]string{
	"Or", "And", "Eq", "Ne", "Lt", "Le", "Gt", "Ge",
	"Add", "Sub", "Concat", "Mul", "Div", "Mod",
	"NullSafeEq", "BitAnd", "BitXor", "Tilde",
}

// String 返回种别名,用于测试失败信息与报错可读。
func (k BinaryOp) String() string {
	if 0 <= int(k) && int(k) < len(binaryOpNames) {
		return binaryOpNames[k]
	}
	return "BinaryOp(" + strconv.Itoa(int(k)) + ")"
}

// Binary 二元运算(Left/Right 为两侧操作数)。
type Binary struct {
	Pos   lexer.Pos
	Op    BinaryOp
	Left  Expr
	Right Expr
}

// IsWhat IS 谓词判定对象(NULL/TRUE/FALSE/UNKNOWN/DISTINCT FROM)。
// 常量加 Is 前缀(与 LiteralKind 的 Null/True/False 撞名,见包注释裁定)。
type IsWhat int

const (
	IsNull IsWhat = iota
	IsTrue
	IsFalse
	IsUnknown
	IsDistinctFrom
)

var isWhatNames = [...]string{"IsNull", "IsTrue", "IsFalse", "IsUnknown", "IsDistinctFrom"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k IsWhat) String() string {
	if 0 <= int(k) && int(k) < len(isWhatNames) {
		return isWhatNames[k]
	}
	return "IsWhat(" + strconv.Itoa(int(k)) + ")"
}

// IsPred IS 谓词;Right 仅 What=IsDistinctFrom 时使用。
type IsPred struct {
	Pos     lexer.Pos
	Operand Expr
	Negated bool
	What    IsWhat
	Right   Expr
}

// Between BETWEEN 谓词;Negated 对应 NOT BETWEEN,Symmetric 对应 SYMMETRIC。
type Between struct {
	Pos       lexer.Pos
	Operand   Expr
	Low       Expr
	High      Expr
	Negated   bool
	Symmetric bool
}

// InPred IN 谓词:值列表(List)或子查询(Subquery)二选一。
type InPred struct {
	Pos      lexer.Pos
	Operand  Expr
	Negated  bool
	List     []Expr
	Subquery *Subquery
}

// LikeKind LIKE 族种别。
type LikeKind int

const (
	Like LikeKind = iota
	ILike
	Similar
)

var likeKindNames = [...]string{"Like", "ILike", "Similar"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k LikeKind) String() string {
	if 0 <= int(k) && int(k) < len(likeKindNames) {
		return likeKindNames[k]
	}
	return "LikeKind(" + strconv.Itoa(int(k)) + ")"
}

// LikePred LIKE/ILIKE/SIMILAR TO 谓词;Escape 为可选 ESCAPE 表达式。
type LikePred struct {
	Pos     lexer.Pos
	Operand Expr
	Pattern Expr
	Negated bool
	Kind    LikeKind
	Escape  Expr
}

// Exists EXISTS(subquery)。
type Exists struct {
	Pos      lexer.Pos
	Subquery *Subquery
}

// Subquery 标量子查询或 IN/EXISTS 的子查询包装。
type Subquery struct {
	Pos   lexer.Pos
	Query Query
}

// FunctionCall 函数调用;Star 对应 COUNT(*),Over 为可选窗口定义
// (OVER 子句内容)。
type FunctionCall struct {
	Pos      lexer.Pos
	Name     Identifier
	Distinct bool
	Star     bool
	Args     []Expr
	Over     *WindowSpec
}

// Case CASE 表达式;Operand 非空为简单 CASE(ELSE 可空)。
type Case struct {
	Pos     lexer.Pos
	Operand Expr
	Whens   []When
	Else    Expr
}

// When 单个 WHEN 分支。
type When struct {
	Cond Expr
	Then Expr
}

// Cast CAST(x AS type)。
type Cast struct {
	Pos     lexer.Pos
	Operand Expr
	Type    TypeSpec
}

// Collate x COLLATE collation。
type Collate struct {
	Pos       lexer.Pos
	Operand   Expr
	Collation Identifier
}

// ---------------------------------------------------------------------------
// 位置访问器与私有标记方法
// ---------------------------------------------------------------------------

func (n *Identifier) Position() lexer.Pos   { return n.Pos }
func (n *Literal) Position() lexer.Pos      { return n.Pos }
func (n *Param) Position() lexer.Pos        { return n.Pos }
func (n *Unary) Position() lexer.Pos        { return n.Pos }
func (n *Binary) Position() lexer.Pos       { return n.Pos }
func (n *IsPred) Position() lexer.Pos       { return n.Pos }
func (n *Between) Position() lexer.Pos      { return n.Pos }
func (n *InPred) Position() lexer.Pos       { return n.Pos }
func (n *LikePred) Position() lexer.Pos     { return n.Pos }
func (n *Exists) Position() lexer.Pos       { return n.Pos }
func (n *Subquery) Position() lexer.Pos     { return n.Pos }
func (n *FunctionCall) Position() lexer.Pos { return n.Pos }
func (n *Case) Position() lexer.Pos         { return n.Pos }
func (n *Cast) Position() lexer.Pos         { return n.Pos }
func (n *Collate) Position() lexer.Pos      { return n.Pos }

func (*Identifier) isExpr()   {}
func (*Literal) isExpr()      {}
func (*Param) isExpr()        {}
func (*Unary) isExpr()        {}
func (*Binary) isExpr()       {}
func (*IsPred) isExpr()       {}
func (*Between) isExpr()      {}
func (*InPred) isExpr()       {}
func (*LikePred) isExpr()     {}
func (*Exists) isExpr()       {}
func (*Subquery) isExpr()     {}
func (*FunctionCall) isExpr() {}
func (*Case) isExpr()         {}
func (*Cast) isExpr()         {}
func (*Collate) isExpr()      {}
