package ast

// ---------------- 表达式 ----------------

// Ident 是（可能多段的）标识符引用。
type Ident struct {
	Pos   Pos
	Parts []IdentPart
}

func (n *Ident) At() Pos { return n.Pos }
func (n *Ident) exprNode() {}

// Star 是表达式位置的 *（仅 COUNT(*) 的参数）或 t.*（SELECT 项）。
type Star struct {
	Pos    Pos
	Prefix []IdentPart // t.* 时的 t
}

func (n *Star) At() Pos { return n.Pos }
func (n *Star) exprNode() {}

// LiteralKind 字面量种类。
type LiteralKind int

const (
	LitNull LiteralKind = iota
	LitTrue
	LitFalse
	LitInt
	LitDecimal
	LitApprox
	LitString
	LitDate
	LitTime
	LitTimestamp
	LitInterval
)

// Literal 是字面量：数与串保存原词/解码值（Text），日期时间/区间带
// 类型前缀与单位（Unit/UnitTo）。
type Literal struct {
	Pos    Pos
	Kind   LiteralKind
	Text   string
	Unit   string // INTERVAL 起始单位
	UnitTo string // INTERVAL [TO unit] 结束单位（可空）
}

func (n *Literal) At() Pos { return n.Pos }
func (n *Literal) exprNode() {}

// Param 是动态参数 ?（Index=-1）或 ?N。
type Param struct {
	Pos   Pos
	Index int
}

func (n *Param) At() Pos { return n.Pos }
func (n *Param) exprNode() {}

// UnaryOp 一元算子。
type UnaryOp int

const (
	UnaryNeg UnaryOp = iota
	UnaryPlus
	UnaryNot
)

// Unary 是一元 + / - / NOT。
type Unary struct {
	Pos Pos
	Op  UnaryOp
	X   Expr
}

func (n *Unary) At() Pos { return n.Pos }
func (n *Unary) exprNode() {}

// BinaryOp 二元算子。
type BinaryOp int

const (
	OpOr BinaryOp = iota
	OpAnd
	OpEq
	OpNe
	OpLt
	OpLe
	OpGt
	OpGe
	OpAdd
	OpSub
	OpConcat
	OpMul
	OpDiv
	OpMod
)

// Binary 是二元运算。
type Binary struct {
	Pos Pos
	Op  BinaryOp
	L   Expr
	R   Expr
}

func (n *Binary) At() Pos { return n.Pos }
func (n *Binary) exprNode() {}

// IsWhat IS 谓词对象。
type IsWhat int

const (
	IsNull IsWhat = iota
	IsTrue
	IsFalse
	IsUnknown
	IsDistinctFrom
)

// IsPred 是 IS [NOT] NULL/TRUE/FALSE/UNKNOWN/DISTINCT FROM。
type IsPred struct {
	Pos  Pos
	X    Expr
	Neg  bool
	What IsWhat
	R    Expr // DistinctFrom 时非空
}

func (n *IsPred) At() Pos { return n.Pos }
func (n *IsPred) exprNode() {}

// Between 是 [NOT] BETWEEN [SYMMETRIC] lo AND hi。
type Between struct {
	Pos       Pos
	X, Low    Expr
	High      Expr
	Neg       bool
	Symmetric bool
}

func (n *Between) At() Pos { return n.Pos }
func (n *Between) exprNode() {}

// InPred 是 [NOT] IN (列表 | 子查询)。
type InPred struct {
	Pos   Pos
	X     Expr
	Neg   bool
	List  []Expr
	Sub   *Subquery
}

func (n *InPred) At() Pos { return n.Pos }
func (n *InPred) exprNode() {}

// LikeKind LIKE / ILIKE / SIMILAR TO。
type LikeKind int

const (
	LikePlain LikeKind = iota
	LikeI
	LikeSimilar
)

// Like 是 [NOT] (LIKE | ILIKE | SIMILAR TO) pattern [ESCAPE e]。
type Like struct {
	Pos     Pos
	X       Expr
	Pattern Expr
	Neg     bool
	Kind    LikeKind
	Esc     Expr
}

func (n *Like) At() Pos { return n.Pos }
func (n *Like) exprNode() {}

// Exists 是 EXISTS (query)。
type Exists struct {
	Pos   Pos
	Query Query
}

func (n *Exists) At() Pos { return n.Pos }
func (n *Exists) exprNode() {}

// Subquery 是表达式位置的标量子查询 (query)。
type Subquery struct {
	Pos   Pos
	Query Query
}

func (n *Subquery) At() Pos { return n.Pos }
func (n *Subquery) exprNode() {}

// Call 是函数/构造调用（含聚合与 OVER 窗口）。Name 已按方言折算。
type Call struct {
	Pos      Pos
	Name     IdentPart
	Distinct bool
	Star     bool // COUNT(*)
	Args     []Expr
	Over     *Window
}

func (n *Call) At() Pos { return n.Pos }
func (n *Call) exprNode() {}

// When 是 CASE WHEN cond THEN v。
type When struct {
	Cond Expr
	Then Expr
}

// Case 是 CASE（两种形态）。
type Case struct {
	Pos     Pos
	Operand Expr // 简单 CASE 时非空
	Whens   []When
	Else    Expr
}

func (n *Case) At() Pos { return n.Pos }
func (n *Case) exprNode() {}

// Cast 是 CAST(x AS type) 或 x::type。
type Cast struct {
	Pos  Pos
	X    Expr
	Type TypeSpec
}

func (n *Cast) At() Pos { return n.Pos }
func (n *Cast) exprNode() {}

// Collate 是 x COLLATE c。
type Collate struct {
	Pos       Pos
	X         Expr
	Collation IdentPart
}

func (n *Collate) At() Pos { return n.Pos }
func (n *Collate) exprNode() {}

// ---------------- 窗口 ----------------

// Window 是 OVER (PARTITION BY ... ORDER BY ... [frame])。
type Window struct {
	Pos         Pos
	PartitionBy []Expr
	Order       []OrderItem
	Frame       *Frame
}

// FrameUnit 帧单位。
type FrameUnit int

const (
	FrameRows FrameUnit = iota
	FrameRange
)

// Frame 是窗口帧；HasEnd=false 时只有 Start 单边界。
type Frame struct {
	Pos    Pos
	Unit   FrameUnit
	Start  FrameBound
	End    FrameBound
	HasEnd bool
}

// BoundKind 帧边界种类。
type BoundKind int

const (
	BoundUnboundedPreceding BoundKind = iota
	BoundPreceding
	BoundCurrentRow
	BoundFollowing
	BoundUnboundedFollowing
)

// FrameBound 是一个帧边界。
type FrameBound struct {
	Kind   BoundKind
	Offset Expr // Preceding/Following 时的偏移
}

// ---------------- 类型 ----------------

// TypeSpec 是 CAST 类型：Name 为规范大写名（DECIMAL / DOUBLE / TIMESTAMP /
// VARCHAR ...），Precision/Scale 可空，Suffix 为 DOUBLE PRECISION 或
// WITH/WITHOUT TIME ZONE 一类尾部修饰。
type TypeSpec struct {
	Pos       Pos
	Name      string
	Precision *int
	Scale     *int
	Suffix    string
}
