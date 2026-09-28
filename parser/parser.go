// Package parser 实现 M1 的手写递归下降 SQL 解析器。
//
// Task 6 交付骨架与完整表达式优先级链;Task 7 交付裸 SELECT 查询文法
// (ParseQuery:SELECT 项/表引用链/逗号与 JOIN/WHERE/GROUP BY/HAVING,
// 见 select.go——IN/EXISTS/标量子查询与派生表均经 ParseQuery 获得完整查询
// 能力);集合运算与排序限尾(Task 8)、WITH/INSERT/CTAS/INSERT OVERWRITE
// (Task 9)在本骨架上扩展,最终由 ParseStatement 统一入口分发。
//
// 结构:Parser 持有方言 Profile 与 lexer.Lex 产出的 token 流及游标;New 在
// 构造时即完成词法分析,词法错误暂存于 lexErr,首次 Parse 调用时原样返回。
//
// 表达式自低向高的优先级链逐字落地 M1 计划决策 7:
//
//	OR → AND → NOT → 谓词层 → 加减与 || → 乘除模 → 一元 + - → primary
//
// 谓词层为单层左结合链(比较运算、IS [NOT] ..、[NOT] IN、[NOT] BETWEEN、
// [NOT] LIKE 族),操作数层级与 Calcite 平面列表 + 优先级攀爬(toTree)的
// 归约结果对齐:`a NOT BETWEEN b AND c AND d` 归约为
// ((a NOT BETWEEN b AND c) AND d)。BETWEEN 上下界在「含 OR、不含 AND」的
// 层级解析(parseOrNoAnd,对齐 SqlBetweenOperator.reduceExpr 的
// toTreeEx(.., 0, AND));LIKE 模式/ESCAPE 与比较右侧在加减层解析。混合链
// (如 `a = b IN (..)`)的树形可能与 Calcite 存在出入,但解析期接受/拒绝
// 边界一致——M1 兼容标准即解析期接受/拒绝边界(task-6 报告记有清单)。
//
// 关键字判定:未引号 Ident 且 strings.EqualFold(atKw);QuotedIdent 永不匹配
// 关键字(决策 3)。标识符大小写折算:未引号段按 Profile.UnquotedCasing、
// 引号段按 Profile.QuotedCasing(foldIdent);AST IdentPart.Value 存折算后
// 文本,Quoted 标记保真。
package parser

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/lexer"
	"io.sqlmask/go/maskerr"
)

// Parser 递归下降解析器:方言 Profile + token 流 + 游标。
type Parser struct {
	profile *dialect.Profile
	toks    []lexer.Token
	cur     int
	// lexErr 暂存 New 阶段的词法错误(ParseExpr/ParseStatement 首查)。
	lexErr error
}

// New 按方言 Profile 构造 Parser 并对 src 完成词法分析。
// 词法失败时错误不在此返回,而由首次 ParseExpr/ParseStatement 调用返回。
func New(p *dialect.Profile, src string) *Parser {
	pp := &Parser{profile: p}
	if p == nil {
		pp.lexErr = maskerr.Errorf(maskerr.ConfigError, "parser: nil dialect profile")
		return pp
	}
	toks, err := lexer.Lex(p, src)
	if err != nil {
		pp.lexErr = err
		return pp
	}
	pp.toks = toks
	return pp
}

// ParseStatement 顶层语句入口。Task 6 为存根:Task 8/9 落语句层后替换。
// 完整入口见 stmt.go(Task 9)。

// ParseExpr 把整个输入解析为单个表达式(表达式到输入末尾,残留 token 报
// 错,语义对齐 Java SqlExpressionEof)。导出供测试与 M2 rowfilter 复用。
func (p *Parser) ParseExpr() (ast.Expr, error) {
	if p.lexErr != nil {
		return nil, p.lexErr
	}
	e, err := p.parseExpr()
	if err != nil {
		return nil, err
	}
	if tok := p.curTok(); tok.Kind != lexer.EOF {
		return nil, p.errAt(tok.Pos, "unexpected token %q after expression; expected end of input", tok.Text)
	}
	return e, nil
}

// ---------------------------------------------------------------------------
// token 流助手
// ---------------------------------------------------------------------------

// curTok 返回当前 token(游标不会越过末尾的 EOF token)。
func (p *Parser) curTok() lexer.Token { return p.toks[p.cur] }

// peekTok 返回当前 token 的下一个 token(越界时返回末尾 EOF token)。
func (p *Parser) peekTok() lexer.Token {
	if p.cur+1 < len(p.toks) {
		return p.toks[p.cur+1]
	}
	return p.toks[len(p.toks)-1]
}

// advance 消费并返回当前 token;已在 EOF 时原地不动。
func (p *Parser) advance() lexer.Token {
	t := p.toks[p.cur]
	if p.cur < len(p.toks)-1 {
		p.cur++
	}
	return t
}

// atKw 判定当前 token 是否为未引号关键字:Kind==Ident 且 strings.EqualFold;
// QuotedIdent 永不匹配(决策 3)。
func (p *Parser) atKw(word string) bool {
	t := p.curTok()
	return t.Kind == lexer.Ident && strings.EqualFold(t.Text, word)
}

// peekKw 判定下一个 token 是否为未引号关键字。
func (p *Parser) peekKw(word string) bool {
	t := p.peekTok()
	return t.Kind == lexer.Ident && strings.EqualFold(t.Text, word)
}

// expectKw 消费一个未引号关键字,否则报错。
func (p *Parser) expectKw(word string) (lexer.Token, error) {
	if !p.atKw(word) {
		t := p.curTok()
		return t, p.errAt(t.Pos, "expected keyword %q, found %s %q", word, t.Kind, t.Text)
	}
	return p.advance(), nil
}

// atOp 判定当前 token 是否为指定运算符。
func (p *Parser) atOp(op string) bool {
	t := p.curTok()
	return t.Kind == lexer.Op && t.Text == op
}

// expectOp 消费一个运算符,否则报错。
func (p *Parser) expectOp(op string) (lexer.Token, error) {
	if !p.atOp(op) {
		t := p.curTok()
		return t, p.errAt(t.Pos, "expected %q, found %s %q", op, t.Kind, t.Text)
	}
	return p.advance(), nil
}

// foldIdent 按 Profile 折算标识符大小写:未引号段按 UnquotedCasing,引号段
// 按 QuotedCasing(五方言引号段均 UNCHANGED,实现按字段值通用处理)。
func (p *Parser) foldIdent(raw string, quoted bool) string {
	casing := p.profile.UnquotedCasing
	if quoted {
		casing = p.profile.QuotedCasing
	}
	if casing == dialect.ToLower {
		return strings.ToLower(raw)
	}
	return raw
}

// errAt 构造带位置前缀的解析错误(错误码恒为 maskerr.PARSE_ERROR,
// message 含 "Line N, Column M")。
func (p *Parser) errAt(pos lexer.Pos, format string, args ...any) error {
	return maskerr.Errorf(maskerr.ParseError, "Parse error at Line %d, Column %d: %s",
		pos.Line, pos.Column, fmt.Sprintf(format, args...))
}

// ---------------------------------------------------------------------------
// 引号串还原(引号标识符与字符串字面量正文)
// ---------------------------------------------------------------------------

// unquoteQuotedIdent 还原引号标识符值:剥掉两端引号字符,内部双写引号
// 还原为单个。引号字符取自源文首字节(" 或 `,按方言)。
func unquoteQuotedIdent(raw string) string {
	// 词法器保证 raw 长度 >= 2 且两端引号一致。
	inner := raw[1 : len(raw)-1]
	q := raw[0]
	if !strings.ContainsRune(inner, rune(q)) {
		return inner
	}
	return strings.ReplaceAll(inner, string([]byte{q, q}), string(q))
}

// unquoteStringLiteral 还原单引号字符串字面量正文:剥掉两端单引号,
// 内部双写单引号还原为单个。
func unquoteStringLiteral(raw string) string {
	inner := raw[1 : len(raw)-1]
	if !strings.Contains(inner, "''") {
		return inner
	}
	return strings.ReplaceAll(inner, "''", "'")
}

// adjacentSameLine 判定两个 token 是否同行紧邻(中间无任何字符):
// 用于 X'..' 二进制字面量与「标识符 + 字符串」(如串别名)的区分——
// Java 词法器对无空白的 x'..' 产单 token,有空白则为两 token。
func adjacentSameLine(a, b lexer.Token) bool {
	return a.Pos.Line == b.Pos.Line && b.Pos.Column == a.Pos.Column+utf8.RuneCountInString(a.Text)
}
