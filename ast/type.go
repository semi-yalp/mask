package ast

import (
	"strconv"

	"io.sqlmask/go/lexer"
)

// ---------------------------------------------------------------------------
// 窗口与类型(TypeSpec/Frame 相关)
// ---------------------------------------------------------------------------

// WindowSpec 窗口定义(OVER 子句内容)。
type WindowSpec struct {
	Pos         lexer.Pos
	PartitionBy []Expr
	Order       []OrderItem
	Frame       *Frame
}

// WindowDef Select.Window 的元素类型;简报仅引用该类型名而未给出定义,
// 落地为 WindowSpec 的类型别名(见包注释裁定)。
type WindowDef = WindowSpec

// FrameUnit 窗口帧单位。
type FrameUnit int

const (
	Rows FrameUnit = iota
	Range
)

var frameUnitNames = [...]string{"Rows", "Range"}

// String 返回种别名,用于测试失败信息与报错可读。
func (k FrameUnit) String() string {
	if 0 <= int(k) && int(k) < len(frameUnitNames) {
		return frameUnitNames[k]
	}
	return "FrameUnit(" + strconv.Itoa(int(k)) + ")"
}

// Frame 窗口帧;End 为零值 FrameBound 表示单边界形式(无 BETWEEN)。
type Frame struct {
	Unit  FrameUnit
	Start FrameBound
	End   FrameBound
}

// BoundKind 帧边界种别。
type BoundKind int

const (
	UnboundedPreceding BoundKind = iota
	Preceding
	CurrentRow
	Following
	UnboundedFollowing
)

var boundKindNames = [...]string{
	"UnboundedPreceding", "Preceding", "CurrentRow", "Following", "UnboundedFollowing",
}

// String 返回种别名,用于测试失败信息与报错可读。
func (k BoundKind) String() string {
	if 0 <= int(k) && int(k) < len(boundKindNames) {
		return boundKindNames[k]
	}
	return "BoundKind(" + strconv.Itoa(int(k)) + ")"
}

// FrameBound 帧边界;Offset 仅 Preceding/Following 使用。
type FrameBound struct {
	Kind   BoundKind
	Offset Expr
}

// TypeSpec 数据类型;Name 为规范化名(如 VARCHAR、DOUBLE PRECISION),
// Precision/Scale 仅数值类型使用。
type TypeSpec struct {
	Pos       lexer.Pos
	Name      string
	Precision Expr
	Scale     Expr
}

// ---------------------------------------------------------------------------
// 位置访问器
// ---------------------------------------------------------------------------

func (n *WindowSpec) Position() lexer.Pos { return n.Pos }
func (n *TypeSpec) Position() lexer.Pos   { return n.Pos }
