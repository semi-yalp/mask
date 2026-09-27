// Package lexer 实现方言感知的 SQL 词法器。
//
// 行为规范来自 task-4 简报与 fix round 1 裁定:关键字不在词法层判定
// (统一 Ident,判定在 parser);引号字符按方言 Profile 取;字符串支持
// 单引号双写转义与 E'/e' 前缀;数字对齐 JavaCC DECIMAL/APPROX(裸 .5、
// 1. 等形式均可,无上下文最长匹配);运算符按 opTable 最长匹配;?/?1 为
// Param;行注释 -- 与 // 及块注释 /* */(不嵌套,第一个 */ 终结)不产
// token;Pos 行列均 1 起,Tab 按制表位 8 推进(JavaCC SimpleCharStream 语义)。
// 词法错误统一为 maskerr.PARSE_ERROR,message 含
// "Lexical error at Line N, Column M"。
//
// 词汇表(关键字/运算符)由 cmd/tokenextract 从 JavaCC 生成的
// SqlMaskParserImplConstants.java 机械提取为 testdata/tokens.json;
// 运算符匹配表 opTable 为程序内常量,内容与 tokens.json 逐项一致
// (由测试双向锁定),lexer 不在运行时读文件。
//
// 已知口径(差分 watchlist,fix round 1 裁定 Ruling 3/4/5/6/8/11/13):
// 未闭合字符串、引号标识符内裸换行、BackTick 方言下的双引号、白名单外
// 字符(~ : ! | 等)在本词法器报词法错,而 Java 版词法层放行、由解析器
// 报 PARSE_ERROR —— 对外错误码一致,消息与位置可能不同,差分按错误码
// 比对;N'…'、x'…'、U&'…'、_charset'…'、[方括号标识符] 等 Java 单 token
// 构造在此拆分为 Ident+String;散落注释外的 */ 为 Op(*)+Op(/)(Java 为
// COMMENT_END token)。以上均列入 watchlist,差分出现时按本口径归因。
package lexer

import "strconv"

// Pos 源码位置,行号与列号均从 1 起;Tab 记 1 列(简报规定的 Calcite 语义)。
//
// 注:Pos 暂落 lexer 包;Task 5 实现 ast 时由控制者统一去留。
type Pos struct {
	Line   int
	Column int
}

// TokenKind 词法种别。词法层不区分关键字 —— 关键字判定在 parser。
type TokenKind int

const (
	// Ident 未引号标识符;Text 为未折算的原文(大小写折叠在 parser 按方言做)。
	Ident TokenKind = iota
	// QuotedIdent 引号标识符;Text 含两端引号与内部双写转义原文。
	QuotedIdent
	// String 字符串字面量;Text 含两端引号原文('' 与 E\' 转义不展开)。
	String
	// Number 数字字面量 D+ [. D+] [E[+-]D+]。
	Number
	// Op 运算符,内容来自 opTable。
	Op
	// Param 动态参数 ? 或 ?N。
	Param
	// EOF 输入结束哨兵,恒为最后一个 token。
	EOF
)

// String 返回种别名,用于测试与报错定位。
func (k TokenKind) String() string {
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
	case EOF:
		return "EOF"
	default:
		return "TokenKind(" + strconv.Itoa(int(k)) + ")"
	}
}

// Token 一个词法单元。
type Token struct {
	Kind TokenKind
	// Text 源文片段:引号串含引号,Ident 为未折算原文,EOF 为空串。
	Text string
	Pos  Pos
}

// opTable 运算符最长匹配表,内容机械来自 testdata/tokens.json 的 operators
// (cmd/tokenextract 从 JavaCC tokenImage 按简报白名单提取)。
// 二字符条目优先于单字符匹配(最长匹配);? 不在此匹配 —— 由 Param 分支处理。
var opTable = []string{
	"+", "-", "*", "/", "%", "=", "<>", "!=", "<", "<=", ">", ">=",
	"(", ")", ",", ".", ";", "||", "::", "=>", "?",
}

// twoOp/oneOp 为 opTable 按长度拆分的查找集(oneOp 不含 ?)。
var (
	twoOp = make(map[string]bool)
	oneOp = make(map[byte]bool)
)

func init() {
	for _, op := range opTable {
		switch len(op) {
		case 2:
			twoOp[op] = true
		case 1:
			if op[0] != '?' {
				oneOp[op[0]] = true
			}
		}
	}
}
