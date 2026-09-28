// Package ast 定义 M1 语法面的 SQL AST:纯数据节点,无行为(仅位置访问器
// 与枚举 String(),后者仅为测试失败信息可读)。parser(T6+)与 engine(T9)
// 全部以本包为准;类型名/字段名与 task-5 简报 Interfaces 块逐字一致,枚举
// 值序 = 简报注释顺序(首值为零值)。
//
// 位置模型落地(控制者 Task 5 裁定 + Go 语言约束):Pos 唯一定义于 lexer
// 包,ast 不重复定义;全部节点字段为 `Pos lexer.Pos`(简报 `Pos Pos` 的落地
// 拼写,字段名逐字)。注意:Go 禁止同一结构体的字段与方法同名 —— 简报的
// “Pos 字段 + Pos() 方法”在 Go 中不可共存(把方法定义在 lexer.Pos 上经嵌入
// 提升也会被节点自身深度 0 的同名字段遮蔽,已实测验证)。因此 Node 接口的
// 位置访问器落地为 Position() lexer.Pos,每个节点返回自身 Pos 字段;若控制
// 者改判“接口必须为 Pos()”,则需将字段整体改名(当前仅 ast 包存在,切换
// 成本最低)。lexer 包未因本任务做任何修改。
//
// 简报之外的两处落地裁定(其余逐字):
//   - IsWhat 常量加 Is 前缀(IsNull/IsTrue/IsFalse/IsUnknown/IsDistinctFrom):
//     Go 同包常量不得与 LiteralKind 的 Null/True/False 重名;统一加前缀保持
//     枚举内部一致,IS 谓词语义本就含 Is。LiteralKind 全部值名保持简报原样。
//   - WindowDef 定义为 WindowSpec 的类型别名:简报在 Select.Window 处引用
//     []WindowDef 但未定义该类型;M1 语料(TPC-DS)无命名窗口,别名最忠实,
//     后续需要命名窗口时再升级为独立结构体。
//
// 接口成员(按简报):Statement = Select/Values/SetOp/With/OrderBy/Insert/
// InsertOverwrite/CreateTable(查询体五节点同时实现 Query,使 ParseStatement
// 能直接返回查询形态语句);Query = Select/Values/SetOp/With/OrderBy(简报
// 注释指定);TableRef = TableNameRef/DerivedTable/Join;Expr = Identifier/
// Literal/Param/Unary/Binary/IsPred/Between/InPred/LikePred/Exists/Subquery/
// FunctionCall/Case/Cast/Collate。
package ast

import (
	"strconv"

	"io.sqlmask/go/lexer"
)

// Node 全部携带源位置的 AST 节点公共接口。
//
// 命名说明:简报规定访问器名为 Pos(),与节点字段 Pos 同名,Go 不允许共存
// (见包注释),故落地为 Position(),返回值语义不变(自身 Pos 字段)。
type Node interface {
	Position() lexer.Pos
}

// Statement 顶层语句节点。
type Statement interface {
	Node
	isStmt()
}

// Query 查询体节点。
type Query interface {
	Node
	isQuery()
}

// TableRef FROM 子句表引用节点。
type TableRef interface {
	Node
	isTableRef()
}

// ---------------------------------------------------------------------------
// 语句
// ---------------------------------------------------------------------------

// Select SELECT 查询(顶层语句与查询体)。
type Select struct {
	Pos      lexer.Pos
	Distinct bool
	Items    []SelectItem
	From     []TableRef
	Where    Expr
	GroupBy  []Expr
	Having   Expr
	Window   []WindowDef
}

// SelectItem 选择项:普通表达式或 *。编码约定:星项 Star 恒为 true——裸 *
// 时 StarQualifier 为空,`t.*`/`s.t.*` 时为限定段(每段一个 IdentPart,不含
// 星号本身;表达式项 Star=false 且 StarQualifier 必须为空。parser 按此构造,
// M3 unparse 依此区分两类项)。
type SelectItem struct {
	Expr          Expr
	Alias         *Identifier
	Star          bool
	StarQualifier []IdentPart
}

// Values VALUES 行集。
type Values struct {
	Pos  lexer.Pos
	Rows [][]Expr
}

// SetOpKind 集合运算种别。
type SetOpKind int

const (
	Union SetOpKind = iota
	Intersect
	Except
)

var setOpKindNames = [...]string{"Union", "Intersect", "Except"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k SetOpKind) String() string {
	if 0 <= int(k) && int(k) < len(setOpKindNames) {
		return setOpKindNames[k]
	}
	return "SetOpKind(" + strconv.Itoa(int(k)) + ")"
}

// SetOp 集合运算(Union/Intersect/Except)。
type SetOp struct {
	Pos   lexer.Pos
	Op    SetOpKind
	All   bool
	Left  Query
	Right Query
}

// With WITH 子句查询(Recursive 对应 WITH RECURSIVE)。
type With struct {
	Pos       lexer.Pos
	Recursive bool
	Items     []WithItem
	Body      Query
}

// WithItem 单个 CTE:名字、可选列名清单与查询体。
type WithItem struct {
	Name    Identifier
	Columns []Identifier
	Body    Query
}

// OrderBy 顶层 ORDER BY/LIMIT 包装(Query 为被包装的查询体)。
type OrderBy struct {
	Pos    lexer.Pos
	Query  Query
	Items  []OrderItem
	Limit  Expr
	Offset Expr
	Fetch  Expr
}

// OrderItem 单个排序项;NullsFirst nil=未指定。
type OrderItem struct {
	Expr       Expr
	Dir        OrderDir
	NullsFirst *bool
}

// OrderDir 排序方向。
type OrderDir int

const (
	Unspecified OrderDir = iota
	Asc
	Desc
)

var orderDirNames = [...]string{"Unspecified", "Asc", "Desc"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k OrderDir) String() string {
	if 0 <= int(k) && int(k) < len(orderDirNames) {
		return orderDirNames[k]
	}
	return "OrderDir(" + strconv.Itoa(int(k)) + ")"
}

// Insert INSERT .. SELECT。
type Insert struct {
	Pos     lexer.Pos
	Target  TableNameRef
	Columns []Identifier
	Source  Query
}

// InsertOverwrite INSERT OVERWRITE [TABLE] .. SELECT(Hive/SparkSQL)。
type InsertOverwrite struct {
	Pos     lexer.Pos
	Target  TableNameRef
	Columns []Identifier
	Source  Query
}

// CreateTableVariant 建表变体。
type CreateTableVariant int

const (
	Plain CreateTableVariant = iota
	Replace
	Volatile
	Set
	Multiset
)

var createTableVariantNames = [...]string{"Plain", "Replace", "Volatile", "Set", "Multiset"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k CreateTableVariant) String() string {
	if 0 <= int(k) && int(k) < len(createTableVariantNames) {
		return createTableVariantNames[k]
	}
	return "CreateTableVariant(" + strconv.Itoa(int(k)) + ")"
}

// CreateTable 建表变体语句;Columns 为可选列名清单(类型不落 AST,M3 从
// unparse 重组),Query 为来源查询(CTAS)。IfNotExists 对应 CREATE TABLE
// IF NOT EXISTS——Task 5 简报 Interfaces 未列,fix round 1(Task 9)控制者
// 裁定新增:M3 compose 重组需要该标记,与 Java SqlCreateTable.ifNotExists
// 对齐;解析层记录,Classify 不消费。
type CreateTable struct {
	Pos         lexer.Pos
	Variant     CreateTableVariant
	IfNotExists bool
	Name        TableNameRef
	Columns     []Identifier
	Query       Query
}

// ---------------------------------------------------------------------------
// 表引用
// ---------------------------------------------------------------------------

// TableNameRef 多段名表引用(如 a.b.c)。
type TableNameRef struct {
	Pos   lexer.Pos
	Parts []Identifier
	Alias *TableAlias
}

// DerivedTable 派生表(子查询 + 别名)。
type DerivedTable struct {
	Pos   lexer.Pos
	Query Query
	Alias *TableAlias
}

// JoinKind 连接种别。
type JoinKind int

const (
	Inner JoinKind = iota
	Left
	Right
	Full
	Cross
	Comma
)

var joinKindNames = [...]string{"Inner", "Left", "Right", "Full", "Cross", "Comma"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k JoinKind) String() string {
	if 0 <= int(k) && int(k) < len(joinKindNames) {
		return joinKindNames[k]
	}
	return "JoinKind(" + strconv.Itoa(int(k)) + ")"
}

// Join 表连接;Natural 对应 NATURAL JOIN,On/Using 二选一
// (Cross/Comma 两者皆空)。
type Join struct {
	Pos     lexer.Pos
	Natural bool
	Kind    JoinKind
	Left    TableRef
	Right   TableRef
	On      Expr
	Using   []Identifier
}

// TableAlias 表别名与可选列别名清单。
type TableAlias struct {
	Name    Identifier
	Columns []Identifier
}

// ---------------------------------------------------------------------------
// 位置访问器与私有标记方法
// ---------------------------------------------------------------------------

func (n *Select) Position() lexer.Pos          { return n.Pos }
func (n *Values) Position() lexer.Pos          { return n.Pos }
func (n *SetOp) Position() lexer.Pos           { return n.Pos }
func (n *With) Position() lexer.Pos            { return n.Pos }
func (n *OrderBy) Position() lexer.Pos         { return n.Pos }
func (n *Insert) Position() lexer.Pos          { return n.Pos }
func (n *InsertOverwrite) Position() lexer.Pos { return n.Pos }
func (n *CreateTable) Position() lexer.Pos     { return n.Pos }
func (n *TableNameRef) Position() lexer.Pos    { return n.Pos }
func (n *DerivedTable) Position() lexer.Pos    { return n.Pos }
func (n *Join) Position() lexer.Pos            { return n.Pos }

func (*Select) isStmt()          {}
func (*Values) isStmt()          {}
func (*SetOp) isStmt()           {}
func (*With) isStmt()            {}
func (*OrderBy) isStmt()         {}
func (*Insert) isStmt()          {}
func (*InsertOverwrite) isStmt() {}
func (*CreateTable) isStmt()     {}

func (*Select) isQuery()  {}
func (*Values) isQuery()  {}
func (*SetOp) isQuery()   {}
func (*With) isQuery()    {}
func (*OrderBy) isQuery() {}

func (*TableNameRef) isTableRef() {}
func (*DerivedTable) isTableRef() {}
func (*Join) isTableRef()         {}
