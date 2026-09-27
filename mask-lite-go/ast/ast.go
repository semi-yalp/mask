// Package ast 定义 mask-lite-go 的 SQL AST。节点集合 = 只读语句面
// （SELECT / WITH / 集合运算 / VALUES / 顶层 ORDER BY / LIMIT / OFFSET /
// FETCH）+ 表引用与表达式（TPC-DS 语料覆盖面）+ 只读门拒绝用的
// Unsupported 标记。方言无关；位置信息仅用于错误消息。
package ast

// Pos 是 1 起算的源位置。字段名与访问器故意分离（At()）以避免命名冲突。
type Pos struct {
	Line   int
	Column int
}

// Node 是所有 AST 节点的公共接口。
type Node interface {
	At() Pos
}

// Statement 是语句级节点。KindLower/KindUpper 供只读门与 classify 族
// 错误消息使用（对齐 Calcite SqlKind 的 lowerName / name）。
type Statement interface {
	Node
	stmtNode()
	KindLower() string
	KindUpper() string
}

// Query 是查询级节点（SELECT / WITH / 集合运算 / VALUES / 括号内带
// ORDER BY 的查询包装）。
type Query interface {
	Node
	queryNode()
}

// TableRef 是 FROM 项。
type TableRef interface {
	Node
	tableRef()
}

// AsStatement 把查询节点转回语句接口（所有查询具体类型都实现两者，
// 断言恒成功；用于遍历器签名收敛）。
func AsStatement(q Query) Statement { return q.(Statement) }

// AsQuery 把语句节点转为查询接口。
func AsQuery(s Statement) Query { return s.(Query) }

// Expr 是表达式节点。
type Expr interface {
	Node
	exprNode()
}

// IdentPart 是标识符的一段：Value 已按方言折算（未引号→小写），Quoted
// 表示源文是双引号标识符。
type IdentPart struct {
	Value  string
	Quoted bool
}

// ---------------- 语句 ----------------

// Select 是 SELECT 语句/子查询。
type Select struct {
	Pos      Pos
	Distinct bool
	Items    []Item
	From     []TableRef
	Where    Expr
	GroupBy  []GroupItem
	Having   Expr
}

func (n *Select) At() Pos        { return n.Pos }
func (n *Select) stmtNode()      {}
func (n *Select) queryNode()     {}
func (n *Select) KindLower() string { return "select" }
func (n *Select) KindUpper() string { return "SELECT" }

// OrderItem 是排序项。
type OrderItem struct {
	Expr  Expr
	Dir   OrderDir
	Nulls NullsOrdering // nil = 未指定
}

// OrderDir 排序方向。
type OrderDir int

const (
	DirUnspecified OrderDir = iota
	DirAsc
	DirDesc
)

// NullsOrdering NULLS FIRST/LAST。
type NullsOrdering int

const (
	NullsUnspecified NullsOrdering = iota
	NullsFirst
	NullsLast
)

// OrderBy 是顶层 ORDER BY / LIMIT / OFFSET / FETCH 包装（镜像 Calcite
// SqlOrderBy）。作为语句出现时只读门放行；作为查询出现时表示括号内
// 的排序子查询。
type OrderBy struct {
	Pos    Pos
	Query  Query
	Items  []OrderItem
	Limit  Expr // LIMIT n；LIMIT ALL 为 nil 且 LimitAll=true
	Offset Expr
	Fetch  Expr // FETCH FIRST/NEXT n [ROW|ROWS] ONLY
	LimitAll bool
}

func (n *OrderBy) At() Pos    { return n.Pos }
func (n *OrderBy) stmtNode()  {}
func (n *OrderBy) queryNode() {}
func (n *OrderBy) KindLower() string { return "order_by" }
func (n *OrderBy) KindUpper() string { return "ORDER_BY" }

// WithItem 是一个 CTE 定义。
type WithItem struct {
	Pos     Pos
	Name    IdentPart
	Columns []IdentPart
	Body    Query
}

// With 是 WITH 子句（statement 或查询位置）。RECURSIVE 仅解析标记，
// 循环引用在血缘期报 LINEAGE_UNKNOWN。
type With struct {
	Pos       Pos
	Recursive bool
	Items     []WithItem
	Body      Query
}

func (n *With) At() Pos    { return n.Pos }
func (n *With) stmtNode()  {}
func (n *With) queryNode() {}
func (n *With) KindLower() string { return "with" }
func (n *With) KindUpper() string { return "WITH" }

// Values 是 VALUES 行集（statement 拒绝；FROM/子查询位置为合法查询）。
type Values struct {
	Pos  Pos
	Rows [][]Expr
}

func (n *Values) At() Pos    { return n.Pos }
func (n *Values) stmtNode()  {}
func (n *Values) queryNode() {}
func (n *Values) KindLower() string { return "values" }
func (n *Values) KindUpper() string { return "VALUES" }

// SetOp 是 UNION / INTERSECT / EXCEPT。
type SetOp struct {
	Pos  Pos
	Op   SetOpKind
	All  bool
	Left Query
	Right Query
}

// SetOpKind 集合运算种类。
type SetOpKind int

const (
	OpUnion SetOpKind = iota
	OpIntersect
	OpExcept
)

func (k SetOpKind) lower() string {
	switch k {
	case OpIntersect:
		return "intersect"
	case OpExcept:
		return "except"
	default:
		return "union"
	}
}

func (k SetOpKind) upper() string {
	switch k {
	case OpIntersect:
		return "INTERSECT"
	case OpExcept:
		return "EXCEPT"
	default:
		return "UNION"
	}
}

func (n *SetOp) At() Pos    { return n.Pos }
func (n *SetOp) stmtNode()  {}
func (n *SetOp) queryNode() {}
func (n *SetOp) KindLower() string { return n.Op.lower() }
func (n *SetOp) KindUpper() string { return n.Op.upper() }

// Unsupported 是"语法认识但不放行"的语句标记（INSERT / CREATE TABLE ...
// / UPDATE 等）。Gate=true 表示走只读门消息族；false 走 classify 消息族。
type Unsupported struct {
	Pos        Pos
	KindLowerN string
	KindUpperN string
	Gate       bool
}

func (n *Unsupported) At() Pos   { return n.Pos }
func (n *Unsupported) stmtNode() {}
func (n *Unsupported) KindLower() string { return n.KindLowerN }
func (n *Unsupported) KindUpper() string { return n.KindUpperN }

// ---------------- SELECT 成分 ----------------

// Item 是一个 SELECT 项：表达式（可带别名）或 * / t.*。
type Item struct {
	Expr    Expr
	Alias   *IdentPart
	Star    bool
	Prefix  []IdentPart // t.* 时的 t；Star=false 时为空
}

// GroupItem 是 GROUP BY 项：普通表达式或 ROLLUP/CUBE/GROUPING SETS。
type GroupItem struct {
	Expr  Expr       // Simple
	Op    GroupOp
	Exprs []Expr     // Rollup/Cube 参数
	Sets  [][]Expr   // GroupingSets 的每个集合
}

// GroupOp 分组扩展种类。
type GroupOp int

const (
	GroupSimple GroupOp = iota
	GroupRollup
	GroupCube
	GroupSets
)

// ---------------- 表引用 ----------------

// TableName 是表引用（1–3 段名，可带别名）。
type TableName struct {
	Pos   Pos
	Parts []IdentPart
	Alias *TableAlias
}

func (n *TableName) At() Pos { return n.Pos }
func (n *TableName) tableRef() {}

// Derived 是派生表 `(query) [AS] alias [(cols)]`（Lateral 标记 LATERAL）。
type Derived struct {
	Pos    Pos
	Query  Query
	Alias  *TableAlias
	Lateral bool
}

func (n *Derived) At() Pos { return n.Pos }
func (n *Derived) tableRef() {}

// Join 是 JOIN（含逗号连接）。
type Join struct {
	Pos     Pos
	Natural bool
	Kind    JoinKind
	Left    TableRef
	Right   TableRef
	On      Expr
	Using   []IdentPart
}

// JoinKind 连接种类。
type JoinKind int

const (
	JoinInner JoinKind = iota
	JoinLeft
	JoinRight
	JoinFull
	JoinCross
	JoinComma
)

func (n *Join) At() Pos { return n.Pos }
func (n *Join) tableRef() {}

// FromParen 是括号包住的 FROM 项（可带别名）。
type FromParen struct {
	Pos   Pos
	Ref   TableRef
	Alias *TableAlias
}

func (n *FromParen) At() Pos { return n.Pos }
func (n *FromParen) tableRef() {}

// Unnest 是 UNNEST(expr) 表函数（解析面保留；注入期对涉及受控表的情形
// fail-closed，血缘期按未知构造处理）。
type Unnest struct {
	Pos   Pos
	Expr  Expr
	Alias *TableAlias
}

func (n *Unnest) At() Pos { return n.Pos }
func (n *Unnest) tableRef() {}

// TableAlias 是表别名与可选列别名清单。
type TableAlias struct {
	Name    IdentPart
	Columns []IdentPart
}
