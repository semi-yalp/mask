// Package lexer 实现 PostgreSQL 单方言词法器：双引号标识符（大小写保留）、
// 未引号标识符（大小写折算交给 parser）、单引号串（'' 双写）、E'...' 转义串、
// dollar 引用、-- 与可嵌套 /* */ 注释、数字（整数/小数/近似数）、?/?N 参数、
// 单/双字符运算符。词法层不区分关键字——关键字判定由 parser 负责。
package lexer

// Pos 是 1 起算的行列位置（错误消息用）。
type Pos struct {
	Line   int
	Column int
}

// Kind 是 token 种别。
type Kind int

const (
	Ident Kind = iota // 未引号标识符；Text 为未折算原文
	QuotedIdent       // 双引号标识符；Text 为去引号内容（"" 还原为 "）
	String            // 字符串字面量；Text 为解码后的值（不含引号）
	Number            // 数字；Text 为源文片段
	Op                // 运算符/标点
	Param             // 动态参数 ? / ?N
	EOF
)

// String 返回种别名（测试诊断用）。
func (k Kind) String() string {
	switch k {
	case Ident:
		return "Ident"
	case QuotedIdent:
		return "QuotedIdent"
	case String:
		return "String"
	case Number:
		return "Number"
	case Op:
		return "Op"
	case Param:
		return "Param"
	default:
		return "EOF"
	}
}

// Token 是词法单元。
type Token struct {
	Kind Kind
	Text string
	Pos  Pos
}
