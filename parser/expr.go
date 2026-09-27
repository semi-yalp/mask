package parser

import (
	"strings"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/lexer"
)

// 本文件实现 Task 6 的完整表达式优先级链(决策 7,自低向高):
//
//	parseExpr     = parseOr                        入口(整个输入到 EOF)
//	parseOr       = parseAnd { OR parseAnd }       最低优先级,左结合
//	parseAnd      = parseNot { AND parseNot }
//	parseNot      = NOT parseNot | parsePred       NOT 高于 AND,低于谓词层
//	parsePred     = parseAdd { 谓词层运算 }*        单层左结合链(见下)
//	parseAdd      = parseMul { (+ | - | ||) parseMul }*   决策 7:|| 落此层
//	parseMul      = parseUnary { (* | / | %) parseUnary }*
//	parseUnary    = (+ | -) parseUnary | parsePrimary
//
// 谓词层单层左结合,含:比较(= <> != < <= > >=)、IS [NOT]
// NULL/TRUE/FALSE/UNKNOWN、IS [NOT] DISTINCT FROM、[NOT] IN (列表|子查询)、
// [NOT] BETWEEN [SYMMETRIC] 低 AND 高、[NOT] LIKE/ILIKE/SIMILAR TO [ESCAPE]。
// 各形态操作数层级对齐 Calcite reduceExpr/toTree 的可观测归约(见包注释)。

// parseExpr 解析完整表达式(优先级链入口)。
func (p *Parser) parseExpr() (ast.Expr, error) { return p.parseOr() }

// parseOr OR 层(最低优先级,左结合)。
func (p *Parser) parseOr() (ast.Expr, error) {
	left, err := p.parseAnd()
	if err != nil {
		return nil, err
	}
	for p.atKw("OR") {
		p.advance()
		right, err := p.parseAnd()
		if err != nil {
			return nil, err
		}
		left = &ast.Binary{Pos: left.Position(), Op: ast.Or, Left: left, Right: right}
	}
	return left, nil
}

// parseOrNoAnd 「含 OR、不含 AND」的完整表达式:用于 BETWEEN 上下界——
// 对齐 SqlBetweenOperator.reduceExpr 的 low 界归约(toTreeEx 停于 AND),
// 使 `a BETWEEN b = c AND d` 的低界可为比较表达式。
func (p *Parser) parseOrNoAnd() (ast.Expr, error) {
	left, err := p.parseNot()
	if err != nil {
		return nil, err
	}
	for p.atKw("OR") {
		p.advance()
		right, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		left = &ast.Binary{Pos: left.Position(), Op: ast.Or, Left: left, Right: right}
	}
	return left, nil
}

// parseAnd AND 层(左结合)。
func (p *Parser) parseAnd() (ast.Expr, error) {
	left, err := p.parseNot()
	if err != nil {
		return nil, err
	}
	for p.atKw("AND") {
		p.advance()
		right, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		left = &ast.Binary{Pos: left.Position(), Op: ast.And, Left: left, Right: right}
	}
	return left, nil
}

// parseNot NOT 层:NOT 高于 AND/OR、低于谓词层(NOT a = b → NOT (a = b))。
func (p *Parser) parseNot() (ast.Expr, error) {
	if p.atKw("NOT") {
		tok := p.advance()
		operand, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		return &ast.Unary{Pos: tok.Pos, Op: ast.Not, Operand: operand}, nil
	}
	return p.parsePred()
}

// comparisonOps 运算符文本 → BinaryOp(<> 与 != 同为 Ne;!= 仅在
// Default 档 conformance 下拒绝,见 parsePred)。
var comparisonOps = map[string]ast.BinaryOp{
	"=":  ast.Eq,
	"<>": ast.Ne,
	"!=": ast.Ne,
	"<":  ast.Lt,
	"<=": ast.Le,
	">":  ast.Gt,
	">=": ast.Ge,
}

// predKwAfterNot 报告 NOT 之后是否为谓词层关键字(供 NOT IN / NOT BETWEEN /
// NOT LIKE / NOT ILIKE / NOT SIMILAR 识别;NOT 后不得直接跟 IS)。
func (p *Parser) predKwAfterNot() bool {
	for _, kw := range []string{"IN", "BETWEEN", "LIKE", "ILIKE", "SIMILAR"} {
		if p.peekKw(kw) {
			return true
		}
	}
	return false
}

// parsePred 谓词层:单层左结合链。
func (p *Parser) parsePred() (ast.Expr, error) {
	left, err := p.parseAdd()
	if err != nil {
		return nil, err
	}
	for {
		tok := p.curTok()
		if tok.Kind == lexer.Op {
			op, ok := comparisonOps[tok.Text]
			if !ok {
				break
			}
			// `!=` 的接受边界由 conformance 档位决定(对齐 Java
			// comp() 的 NE2 分支 isBangEqualAllowed):Default 档
			// (pg/trino)拒绝;MySQL5/Lenient(mysql/hive/sparksql)
			// 接受并归一为 ast.Ne。
			if tok.Text == "!=" && p.profile.Conformance == dialect.Default {
				return nil, p.errAt(tok.Pos, "Bang equal '!=' is not allowed under the current SQL conformance level")
			}
			p.advance()
			right, err := p.parseAdd()
			if err != nil {
				return nil, err
			}
			left = &ast.Binary{Pos: left.Position(), Op: op, Left: left, Right: right}
			continue
		}
		if tok.Kind != lexer.Ident {
			break
		}
		// NOT 前缀仅在其后确为谓词关键字时归入谓词(NOT IN/BETWEEN/LIKE 族),
		// 否则留给上层(NOT a AND b、NOT a OR b 等)。
		negated := false
		if p.atKw("NOT") && p.predKwAfterNot() {
			p.advance()
			negated = true
		}
		switch {
		case p.atKw("IS"):
			next, err := p.parseIsPred(left, negated)
			if err != nil {
				return nil, err
			}
			left = next
		case p.atKw("IN"):
			next, err := p.parseInPred(left, negated)
			if err != nil {
				return nil, err
			}
			left = next
		case p.atKw("BETWEEN"):
			next, err := p.parseBetweenPred(left, negated)
			if err != nil {
				return nil, err
			}
			left = next
		case p.atKw("LIKE"), p.atKw("ILIKE"), p.atKw("SIMILAR"):
			next, err := p.parseLikePred(left, negated)
			if err != nil {
				return nil, err
			}
			left = next
		default:
			if negated {
				// predKwAfterNot 已保证 NOT 后必为谓词关键字,不可达。
				return nil, p.errAt(tok.Pos, "unexpected NOT")
			}
			return left, nil
		}
	}
	return left, nil
}

// parseIsPred IS [NOT] NULL/TRUE/FALSE/UNKNOWN / IS [NOT] DISTINCT FROM b。
func (p *Parser) parseIsPred(operand ast.Expr, negated bool) (ast.Expr, error) {
	p.advance() // IS
	switch {
	case p.atKw("NOT"):
		if negated {
			return nil, p.errAt(p.curTok().Pos, "unexpected NOT after IS")
		}
		p.advance()
		negated = true
		return p.parseIsPredTail(operand, negated)
	default:
		return p.parseIsPredTail(operand, negated)
	}
}

// parseIsPredTail IS 谓词判定对象。
func (p *Parser) parseIsPredTail(operand ast.Expr, negated bool) (ast.Expr, error) {
	pos := operand.Position()
	switch {
	case p.atKw("NULL"):
		p.advance()
		return &ast.IsPred{Pos: pos, Operand: operand, Negated: negated, What: ast.IsNull}, nil
	case p.atKw("TRUE"):
		p.advance()
		return &ast.IsPred{Pos: pos, Operand: operand, Negated: negated, What: ast.IsTrue}, nil
	case p.atKw("FALSE"):
		p.advance()
		return &ast.IsPred{Pos: pos, Operand: operand, Negated: negated, What: ast.IsFalse}, nil
	case p.atKw("UNKNOWN"):
		p.advance()
		return &ast.IsPred{Pos: pos, Operand: operand, Negated: negated, What: ast.IsUnknown}, nil
	case p.atKw("DISTINCT"):
		p.advance()
		if _, err := p.expectKw("FROM"); err != nil {
			return nil, err
		}
		right, err := p.parseAdd()
		if err != nil {
			return nil, err
		}
		return &ast.IsPred{Pos: pos, Operand: operand, Negated: negated,
			What: ast.IsDistinctFrom, Right: right}, nil
	default:
		t := p.curTok()
		return nil, p.errAt(t.Pos, "expected NULL, TRUE, FALSE, UNKNOWN or DISTINCT FROM after IS, found %q", t.Text)
	}
}

// parseInPred [NOT] IN (expr, ...) | [NOT] IN (子查询)。列表元素为完整
// 表达式(对齐 Calcite ExpressionCommaList)。
func (p *Parser) parseInPred(operand ast.Expr, negated bool) (ast.Expr, error) {
	p.advance() // IN
	paren, err := p.expectOp("(")
	if err != nil {
		return nil, err
	}
	pred := &ast.InPred{Pos: operand.Position(), Operand: operand, Negated: negated}
	if p.atKw("SELECT") {
		q, err := p.parseQueryMinimal()
		if err != nil {
			return nil, err
		}
		pred.Subquery = &ast.Subquery{Pos: paren.Pos, Query: q}
	} else {
		for {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			pred.List = append(pred.List, e)
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return pred, nil
}

// parseBetweenPred [NOT] BETWEEN [SYMMETRIC|ASYMMETRIC] low AND high。
// 上下界在「含 OR 不含 AND」层级解析,使 BETWEEN 恰好吃掉属于自己的 AND。
func (p *Parser) parseBetweenPred(operand ast.Expr, negated bool) (ast.Expr, error) {
	p.advance() // BETWEEN
	symmetric := false
	if p.atKw("SYMMETRIC") {
		p.advance()
		symmetric = true
	} else if p.atKw("ASYMMETRIC") {
		p.advance()
	}
	low, err := p.parseOrNoAnd()
	if err != nil {
		return nil, err
	}
	if _, err := p.expectKw("AND"); err != nil {
		return nil, err
	}
	high, err := p.parseOrNoAnd()
	if err != nil {
		return nil, err
	}
	return &ast.Between{Pos: operand.Position(), Operand: operand,
		Low: low, High: high, Negated: negated, Symmetric: symmetric}, nil
}

// parseLikePred [NOT] LIKE/ILIKE/SIMILAR TO pattern [ESCAPE escape]。
// 模式与 ESCAPE 操作数在加减层解析(对齐 Calcite LikeOperator.reduceExpr 的
// 右界:比较及以下优先级终止该操作数)。
func (p *Parser) parseLikePred(operand ast.Expr, negated bool) (ast.Expr, error) {
	var kind ast.LikeKind
	switch {
	case p.atKw("LIKE"):
		p.advance()
		kind = ast.Like
	case p.atKw("ILIKE"):
		p.advance()
		kind = ast.ILike
	default:
		p.advance() // SIMILAR
		if _, err := p.expectKw("TO"); err != nil {
			return nil, err
		}
		kind = ast.Similar
	}
	pattern, err := p.parseAdd()
	if err != nil {
		return nil, err
	}
	pred := &ast.LikePred{Pos: operand.Position(), Operand: operand,
		Pattern: pattern, Negated: negated, Kind: kind}
	if p.atKw("ESCAPE") {
		p.advance()
		esc, err := p.parseAdd()
		if err != nil {
			return nil, err
		}
		pred.Escape = esc
	}
	return pred, nil
}

// ---------------------------------------------------------------------------
// 加减与 || → 乘除模 → 一元
// ---------------------------------------------------------------------------

// parseAdd 加减与字符串连接层(决策 7:|| 落在本层,左结合)。
func (p *Parser) parseAdd() (ast.Expr, error) {
	left, err := p.parseMul()
	if err != nil {
		return nil, err
	}
	for {
		var op ast.BinaryOp
		switch {
		case p.atOp("+"):
			op = ast.Add
		case p.atOp("-"):
			op = ast.Sub
		case p.atOp("||"):
			op = ast.Concat
		default:
			return left, nil
		}
		p.advance()
		right, err := p.parseMul()
		if err != nil {
			return nil, err
		}
		left = &ast.Binary{Pos: left.Position(), Op: op, Left: left, Right: right}
	}
}

// parseMul 乘除模层(左结合)。
func (p *Parser) parseMul() (ast.Expr, error) {
	left, err := p.parseUnary()
	if err != nil {
		return nil, err
	}
	for {
		var op ast.BinaryOp
		switch {
		case p.atOp("*"):
			op = ast.Mul
		case p.atOp("/"):
			op = ast.Div
		case p.atOp("%"):
			op = ast.Mod
		default:
			return left, nil
		}
		p.advance()
		right, err := p.parseUnary()
		if err != nil {
			return nil, err
		}
		left = &ast.Binary{Pos: left.Position(), Op: op, Left: left, Right: right}
	}
}

// parseUnary 一元 +/-(右结合,紧贴 primary)。
func (p *Parser) parseUnary() (ast.Expr, error) {
	tok := p.curTok()
	if tok.Kind == lexer.Op && (tok.Text == "-" || tok.Text == "+") {
		p.advance()
		operand, err := p.parseUnary()
		if err != nil {
			return nil, err
		}
		op := ast.Neg
		if tok.Text == "+" {
			op = ast.Plus
		}
		return &ast.Unary{Pos: tok.Pos, Op: op, Operand: operand}, nil
	}
	return p.parsePrimary()
}

// ---------------------------------------------------------------------------
// primary
// ---------------------------------------------------------------------------

// parsePrimary 一元之下的原子:字面量(整数/小数/近似数/字符串/布尔/NULL/
// DATE/TIME/TIMESTAMP/INTERVAL)、动态参数、标识符(多段)、函数调用、
// 括号表达式、标量子查询、CASE、CAST、EXISTS。决策 5 的 M1 不支持构造在
// 相应分支报 PARSE_ERROR。
func (p *Parser) parsePrimary() (ast.Expr, error) {
	tok := p.curTok()
	switch tok.Kind {
	case lexer.Number:
		p.advance()
		return &ast.Literal{Pos: tok.Pos, Kind: numberLiteralKind(tok.Text), Text: tok.Text}, nil
	case lexer.String:
		p.advance()
		return &ast.Literal{Pos: tok.Pos, Kind: ast.String, Text: tok.Text}, nil
	case lexer.Param:
		p.advance()
		return paramNode(tok), nil
	case lexer.Op:
		if tok.Text == "(" {
			return p.parseParenOrSubquery()
		}
		return nil, p.errAt(tok.Pos, "unexpected operator %q in expression", tok.Text)
	case lexer.QuotedIdent:
		return p.parseIdentOrCall()
	case lexer.Ident:
		switch {
		case p.atKw("NULL"):
			p.advance()
			return &ast.Literal{Pos: tok.Pos, Kind: ast.Null, Text: tok.Text}, nil
		case p.atKw("TRUE"):
			p.advance()
			return &ast.Literal{Pos: tok.Pos, Kind: ast.True, Text: tok.Text}, nil
		case p.atKw("FALSE"):
			p.advance()
			return &ast.Literal{Pos: tok.Pos, Kind: ast.False, Text: tok.Text}, nil
		case (p.atKw("DATE") || p.atKw("TIME") || p.atKw("TIMESTAMP")) && p.peekTok().Kind == lexer.String:
			// DATE/TIME/TIMESTAMP 仅在紧随字符串字面量时为日期时间字面量
			// (对齐 Java LOOKAHEAD(2);否则作为普通标识符,与 Java 一致)。
			p.advance()
			s := p.advance()
			kind := ast.Date
			switch {
			case strings.EqualFold(tok.Text, "TIME"):
				kind = ast.Time
			case strings.EqualFold(tok.Text, "TIMESTAMP"):
				kind = ast.Timestamp
			}
			return &ast.Literal{Pos: tok.Pos, Kind: kind, Text: s.Text}, nil
		case p.atKw("INTERVAL"):
			return p.parseIntervalLiteral()
		case p.atKw("CASE"):
			return p.parseCase()
		case p.atKw("CAST"):
			return p.parseCast()
		case p.atKw("EXISTS"):
			return p.parseExists()
		default:
			return p.parseIdentOrCall()
		}
	default: // EOF
		return nil, p.errAt(tok.Pos, "expected expression, found end of input")
	}
}

// numberLiteralKind 按数字源文形态分级:含指数 → 近似数;含小数点 →
// 小数(含 "1." 与 ".5" 形态);否则整数。
func numberLiteralKind(text string) ast.LiteralKind {
	if strings.ContainsAny(text, "eE") {
		return ast.Approx
	}
	if strings.Contains(text, ".") {
		return ast.Decimal
	}
	return ast.Int
}

// paramNode 构造动态参数节点:? 为匿名(Index=-1),?N 为 Index=N
// (词法器把 ?N 产为单 token,Text 含数字)。
func paramNode(tok lexer.Token) *ast.Param {
	if len(tok.Text) > 1 {
		return &ast.Param{Pos: tok.Pos, Index: parseInt(tok.Text[1:])}
	}
	return &ast.Param{Pos: tok.Pos, Index: -1}
}

// parseParenOrSubquery 「(」开头:后随 SELECT 为标量子查询,否则为括号
// 表达式(行构造器 (a, b) 不在 M1 支持面,由 expectOp(")") 拒绝)。
func (p *Parser) parseParenOrSubquery() (ast.Expr, error) {
	paren := p.advance() // (
	if p.atKw("SELECT") {
		q, err := p.parseQueryMinimal()
		if err != nil {
			return nil, err
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, err
		}
		return &ast.Subquery{Pos: paren.Pos, Query: q}, nil
	}
	e, err := p.parseExpr()
	if err != nil {
		return nil, err
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return e, nil
}

// parseQueryMinimal 子查询体的最小查询解析:SELECT [DISTINCT|ALL] 表达式
// 列表(无 FROM/WHERE 等)。Task 7 以完整 ParseQuery 替换本存根。
func (p *Parser) parseQueryMinimal() (*ast.Select, error) {
	tok := p.advance() // SELECT
	sel := &ast.Select{Pos: tok.Pos}
	switch {
	case p.atKw("DISTINCT"):
		p.advance()
		sel.Distinct = true
	case p.atKw("ALL"):
		p.advance()
	}
	for {
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		sel.Items = append(sel.Items, ast.SelectItem{Expr: e})
		if p.atOp(",") {
			p.advance()
			continue
		}
		return sel, nil
	}
}

// parseIntervalLiteral INTERVAL '正文' [单位 [TO 单位]](决策 6;单位必填,
// 对齐 Java IntervalLiteral 的 IntervalQualifier)。可选前置正负号折入正文。
func (p *Parser) parseIntervalLiteral() (ast.Expr, error) {
	tok := p.advance() // INTERVAL
	sign := ""
	if p.atOp("-") {
		p.advance()
		sign = "-"
	} else if p.atOp("+") {
		p.advance()
	}
	s := p.curTok()
	if s.Kind != lexer.String {
		return nil, p.errAt(s.Pos, "expected string literal after INTERVAL, found %s %q", s.Kind, s.Text)
	}
	p.advance()
	start, end, err := p.parseIntervalUnits()
	if err != nil {
		return nil, err
	}
	lit := &ast.Literal{Pos: tok.Pos, Kind: ast.Interval, Text: s.Text,
		Interval: ast.IntervalLit{Text: sign + unquoteStringLiteral(s.Text), StartUnit: start, EndUnit: end}}
	return lit, nil
}

// intervalUnits INTERVAL 合法单位与 TO 组合(对齐 Java IntervalQualifier
// 的产生式形状:YEAR[TO MONTH] / QUARTER / MONTH / WEEK /
// DAY[TO HOUR|MINUTE|SECOND] / HOUR[TO MINUTE|SECOND] / MINUTE[TO SECOND] /
// SECOND)。
var intervalUnits = map[string]bool{
	"YEAR": true, "QUARTER": true, "MONTH": true, "WEEK": true,
	"DAY": true, "HOUR": true, "MINUTE": true, "SECOND": true,
}

// intervalEndUnits 起始单位允许的 TO 结束单位(空集合表示不允许 TO)。
var intervalEndUnits = map[string]map[string]bool{
	"YEAR":   {"MONTH": true},
	"DAY":    {"HOUR": true, "MINUTE": true, "SECOND": true},
	"HOUR":   {"MINUTE": true, "SECOND": true},
	"MINUTE": {"SECOND": true},
}

// parseIntervalUnits 解析 INTERVAL 字面量单位,返回起止单位(大写;无结束
// 单位时为空串)。缺失或非法组合报错。
func (p *Parser) parseIntervalUnits() (string, string, error) {
	t := p.curTok()
	if t.Kind != lexer.Ident || !intervalUnits[strings.ToUpper(t.Text)] {
		return "", "", p.errAt(t.Pos, "expected interval unit (YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE, SECOND), found %q", t.Text)
	}
	start := strings.ToUpper(p.advance().Text)
	if !p.atKw("TO") {
		return start, "", nil
	}
	p.advance()
	t = p.curTok()
	if t.Kind != lexer.Ident || !intervalUnits[strings.ToUpper(t.Text)] {
		return "", "", p.errAt(t.Pos, "expected interval unit after TO, found %q", t.Text)
	}
	end := strings.ToUpper(p.advance().Text)
	if !intervalEndUnits[start][end] {
		return "", "", p.errAt(t.Pos, "invalid interval unit combination %s TO %s", start, end)
	}
	return start, end, nil
}

// parseCase CASE 表达式两形态:后随 WHEN 为 searched,否则为 simple
// (操作数为完整表达式)。WHEN 至少一个,ELSE 可缺省。
func (p *Parser) parseCase() (ast.Expr, error) {
	tok := p.advance() // CASE
	c := &ast.Case{Pos: tok.Pos}
	if !p.atKw("WHEN") {
		operand, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		c.Operand = operand
	}
	for p.atKw("WHEN") {
		p.advance()
		cond, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if _, err := p.expectKw("THEN"); err != nil {
			return nil, err
		}
		then, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		c.Whens = append(c.Whens, ast.When{Cond: cond, Then: then})
	}
	if len(c.Whens) == 0 {
		t := p.curTok()
		return nil, p.errAt(t.Pos, "CASE requires at least one WHEN branch, found %s %q", t.Kind, t.Text)
	}
	if p.atKw("ELSE") {
		p.advance()
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		c.Else = e
	}
	if _, err := p.expectKw("END"); err != nil {
		return nil, err
	}
	return c, nil
}

// parseCast CAST(expr AS type);类型名支持面与规范化见 parseTypeName(决策 8)。
func (p *Parser) parseCast() (ast.Expr, error) {
	tok := p.advance() // CAST
	if _, err := p.expectOp("("); err != nil {
		return nil, err
	}
	operand, err := p.parseExpr()
	if err != nil {
		return nil, err
	}
	if _, err := p.expectKw("AS"); err != nil {
		return nil, err
	}
	ts, err := p.parseTypeName()
	if err != nil {
		return nil, err
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return &ast.Cast{Pos: tok.Pos, Operand: operand, Type: ts}, nil
}

// parseExists EXISTS (子查询)。
func (p *Parser) parseExists() (ast.Expr, error) {
	tok := p.advance() // EXISTS
	paren, err := p.expectOp("(")
	if err != nil {
		return nil, err
	}
	if !p.atKw("SELECT") {
		t := p.curTok()
		return nil, p.errAt(t.Pos, "expected SELECT after EXISTS (, found %s %q", t.Kind, t.Text)
	}
	q, err := p.parseQueryMinimal()
	if err != nil {
		return nil, err
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return &ast.Exists{Pos: tok.Pos, Subquery: &ast.Subquery{Pos: paren.Pos, Query: q}}, nil
}

// parseIdentOrCall 标识符或函数调用的分派:标识符链后随「(」为函数调用
// (含 COUNT(*)、DISTINCT 聚合、OVER 窗口);同时在此拒绝 M1 不支持的
// X'..' 二进制字面量(Java 词法器把紧邻的 x'..' 产为单 token,这里按
// 「同行紧邻」还原判定)、UNNEST/TABLESAMPLE 表函数(决策 5)与保留字
// ROLLUP/LATERAL(Java 实测:表达式语境 "Incorrect syntax near the
// keyword 'ROLLUP'"/'LATERAL';CUBE/GROUPING/SETS 单词形式 Java 解析
// 接受、到校验期才失败,故按普通函数调用放行)。
func (p *Parser) parseIdentOrCall() (ast.Expr, error) {
	tok := p.curTok()
	if tok.Kind == lexer.Ident {
		if strings.EqualFold(tok.Text, "ROLLUP") || strings.EqualFold(tok.Text, "LATERAL") {
			return nil, p.errAt(tok.Pos, "Incorrect syntax near the keyword '%s'", strings.ToUpper(tok.Text))
		}
		if strings.EqualFold(tok.Text, "X") {
			if nxt := p.peekTok(); nxt.Kind == lexer.String && adjacentSameLine(tok, nxt) {
				return nil, p.errAt(tok.Pos, "binary string literal X%s is not supported", nxt.Text)
			}
		}
	}
	id, err := p.parseIdentifier()
	if err != nil {
		return nil, err
	}
	if !p.atOp("(") {
		return &ast.Identifier{Pos: id.Pos, Parts: id.Parts}, nil
	}
	return p.parseFunctionCall(id)
}

// parseFunctionCall 解析函数调用实参表与可选 OVER 窗口:
//
//	name ( [*] | [DISTINCT|ALL] [expr {, expr}*] ) [OVER (...)]
//
// 决策 5:聚合 FILTER (WHERE ...) 子句遇之即报。
func (p *Parser) parseFunctionCall(name ast.Identifier) (ast.Expr, error) {
	if len(name.Parts) == 1 && !name.Parts[0].Quoted {
		v := name.Parts[0].Value
		if strings.EqualFold(v, "UNNEST") || strings.EqualFold(v, "TABLESAMPLE") {
			return nil, p.errAt(name.Pos, "%s table function is not supported", strings.ToUpper(v))
		}
	}
	p.advance() // (
	fc := &ast.FunctionCall{Pos: name.Pos, Name: name}
	if p.atOp("*") {
		p.advance()
		fc.Star = true
	} else {
		if p.atKw("DISTINCT") {
			p.advance()
			fc.Distinct = true
		} else if p.atKw("ALL") {
			p.advance()
		}
		if p.atOp("*") {
			p.advance()
			fc.Star = true
		} else if !p.atOp(")") {
			for {
				e, err := p.parseExpr()
				if err != nil {
					return nil, err
				}
				fc.Args = append(fc.Args, e)
				if p.atOp(",") {
					p.advance()
					continue
				}
				break
			}
		}
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	if p.atKw("OVER") {
		over, err := p.parseWindowSpec()
		if err != nil {
			return nil, err
		}
		fc.Over = over
	}
	if p.atKw("FILTER") {
		return nil, p.errAt(p.curTok().Pos, "FILTER (WHERE ...) aggregate clause is not supported")
	}
	return fc, nil
}

// parseIdentifier 解析多段标识符 a.b.c(各段按 foldIdent 折算,Quoted 保真);
// 段分隔符「.」之后必须是标识符,否则停止(如 a.* 的星号留给调用方处理)。
func (p *Parser) parseIdentifier() (ast.Identifier, error) {
	tok := p.curTok()
	if tok.Kind != lexer.Ident && tok.Kind != lexer.QuotedIdent {
		return ast.Identifier{}, p.errAt(tok.Pos, "expected identifier, found %s %q", tok.Kind, tok.Text)
	}
	p.advance()
	quoted := tok.Kind == lexer.QuotedIdent
	value := tok.Text
	if quoted {
		value = unquoteQuotedIdent(tok.Text)
	}
	id := ast.Identifier{Pos: tok.Pos, Parts: []ast.IdentPart{
		{Value: p.foldIdent(value, quoted), Quoted: quoted}}}
	for p.atOp(".") {
		nxt := p.peekTok()
		if nxt.Kind != lexer.Ident && nxt.Kind != lexer.QuotedIdent {
			break
		}
		p.advance() // .
		t := p.advance()
		quoted := t.Kind == lexer.QuotedIdent
		value := t.Text
		if quoted {
			value = unquoteQuotedIdent(t.Text)
		}
		id.Parts = append(id.Parts, ast.IdentPart{Value: p.foldIdent(value, quoted), Quoted: quoted})
	}
	return id, nil
}

// parseWindowSpec 解析 OVER 子句体(内联窗口):
//
//	( [PARTITION BY expr {, expr}*] [ORDER BY orderItem {, orderItem}*]
//	  [ROWS|RANGE 帧] )
//
// 帧:BETWEEN 界 AND 界 | 单边界(End 落零值 FrameBound,Task 5 交接约定)。
func (p *Parser) parseWindowSpec() (*ast.WindowSpec, error) {
	p.advance() // OVER
	paren, err := p.expectOp("(")
	if err != nil {
		return nil, err
	}
	ws := &ast.WindowSpec{Pos: paren.Pos}
	if p.atKw("PARTITION") {
		p.advance()
		if _, err := p.expectKw("BY"); err != nil {
			return nil, err
		}
		for {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			ws.PartitionBy = append(ws.PartitionBy, e)
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
	}
	if p.atKw("ORDER") {
		p.advance()
		if _, err := p.expectKw("BY"); err != nil {
			return nil, err
		}
		for {
			item, err := p.parseOrderItem()
			if err != nil {
				return nil, err
			}
			ws.Order = append(ws.Order, item)
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
	}
	if p.atKw("ROWS") || p.atKw("RANGE") {
		frame, err := p.parseFrame()
		if err != nil {
			return nil, err
		}
		ws.Frame = frame
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return ws, nil
}

// parseOrderItem 窗口 ORDER BY 单项:expr [ASC|DESC] [NULLS FIRST|LAST]。
func (p *Parser) parseOrderItem() (ast.OrderItem, error) {
	e, err := p.parseExpr()
	if err != nil {
		return ast.OrderItem{}, err
	}
	item := ast.OrderItem{Expr: e}
	switch {
	case p.atKw("ASC"):
		p.advance()
		item.Dir = ast.Asc
	case p.atKw("DESC"):
		p.advance()
		item.Dir = ast.Desc
	}
	if p.atKw("NULLS") {
		p.advance()
		switch {
		case p.atKw("FIRST"):
			p.advance()
			item.NullsFirst = newBool(true)
		case p.atKw("LAST"):
			p.advance()
			item.NullsFirst = newBool(false)
		default:
			t := p.curTok()
			return ast.OrderItem{}, p.errAt(t.Pos, "expected FIRST or LAST after NULLS, found %q", t.Text)
		}
	}
	return item, nil
}

// parseFrame 解析窗口帧:ROWS|RANGE(决定 FrameUnit)后随
// BETWEEN 界 AND 界(双边界)或单边界(End 为零值 FrameBound)。
func (p *Parser) parseFrame() (*ast.Frame, error) {
	tok := p.advance() // ROWS|RANGE
	unit := ast.Rows
	if strings.EqualFold(tok.Text, "RANGE") {
		unit = ast.Range
	}
	fr := &ast.Frame{Unit: unit}
	if p.atKw("BETWEEN") {
		p.advance()
		start, err := p.parseFrameBound()
		if err != nil {
			return nil, err
		}
		if _, err := p.expectKw("AND"); err != nil {
			return nil, err
		}
		end, err := p.parseFrameBound()
		if err != nil {
			return nil, err
		}
		fr.Start, fr.End = start, end
		return fr, nil
	}
	start, err := p.parseFrameBound()
	if err != nil {
		return nil, err
	}
	fr.Start = start
	return fr, nil
}

// parseFrameBound 帧边界:UNBOUNDED PRECEDING|FOLLOWING / CURRENT ROW /
// expr PRECEDING|FOLLOWING。
func (p *Parser) parseFrameBound() (ast.FrameBound, error) {
	switch {
	case p.atKw("UNBOUNDED"):
		p.advance()
		switch {
		case p.atKw("PRECEDING"):
			p.advance()
			return ast.FrameBound{Kind: ast.UnboundedPreceding}, nil
		case p.atKw("FOLLOWING"):
			p.advance()
			return ast.FrameBound{Kind: ast.UnboundedFollowing}, nil
		default:
			t := p.curTok()
			return ast.FrameBound{}, p.errAt(t.Pos, "expected PRECEDING or FOLLOWING after UNBOUNDED, found %q", t.Text)
		}
	case p.atKw("CURRENT"):
		p.advance()
		if _, err := p.expectKw("ROW"); err != nil {
			return ast.FrameBound{}, err
		}
		return ast.FrameBound{Kind: ast.CurrentRow}, nil
	}
	offset, err := p.parseExpr()
	if err != nil {
		return ast.FrameBound{}, err
	}
	switch {
	case p.atKw("PRECEDING"):
		p.advance()
		return ast.FrameBound{Kind: ast.Preceding, Offset: offset}, nil
	case p.atKw("FOLLOWING"):
		p.advance()
		return ast.FrameBound{Kind: ast.Following, Offset: offset}, nil
	default:
		t := p.curTok()
		return ast.FrameBound{}, p.errAt(t.Pos, "expected PRECEDING or FOLLOWING in frame bound, found %s %q", t.Kind, t.Text)
	}
}

// newBool 返回 bool 值的指针(NullsFirst 用)。
func newBool(b bool) *bool { return &b }

// parseInt 解析十进制整数(动态参数 ?N 的 N;词法器保证为 ASCII 数字)。
func parseInt(s string) int {
	n := 0
	for i := 0; i < len(s); i++ {
		n = n*10 + int(s[i]-'0')
	}
	return n
}

// ---------------------------------------------------------------------------
// CAST 类型名(决策 8 清单;清单外类型名 → PARSE_ERROR)
// ---------------------------------------------------------------------------

// parseTypeName 解析 CAST(x AS type) 的类型名并规范化:
//
//	BOOLEAN;INTEGER/INT→INTEGER;BIGINT;SMALLINT;TINYINT;REAL;
//	FLOAT[(n)];DOUBLE[ PRECISION](按源文大小写规范化保留);
//	DECIMAL/DEC/NUMERIC→DECIMAL/NUMERIC(DEC 折 DECIMAL,同 Calcite),
//	可带 (p[,s]);CHAR[ACTER]→CHAR、CHAR[ACTER] VARYING/VARCHAR→VARCHAR,
//	可带 (n);DATE;TIME[(p)]/[WITH [LOCAL]|WITHOUT TIME ZONE];
//	TIMESTAMP[(p)]/[WITH [LOCAL]|WITHOUT TIME ZONE];
//	BINARY[(n)]、BINARY VARYING→VARBINARY、VARBINARY[(n)];
//	INTERVAL 单位 [TO 单位](组合对齐 Java IntervalQualifier)。
func (p *Parser) parseTypeName() (ast.TypeSpec, error) {
	tok := p.curTok()
	if tok.Kind != lexer.Ident {
		return ast.TypeSpec{}, p.errAt(tok.Pos, "expected type name, found %s %q", tok.Kind, tok.Text)
	}
	word := strings.ToUpper(tok.Text)
	switch word {
	case "BOOLEAN":
		p.advance()
		return ast.TypeSpec{Pos: tok.Pos, Name: "BOOLEAN"}, nil
	case "INT", "INTEGER":
		p.advance()
		return ast.TypeSpec{Pos: tok.Pos, Name: "INTEGER"}, nil
	case "TINYINT", "SMALLINT", "BIGINT", "REAL":
		p.advance()
		return ast.TypeSpec{Pos: tok.Pos, Name: word}, nil
	case "FLOAT":
		p.advance()
		prec, err := p.parsePrecisionParen()
		if err != nil {
			return ast.TypeSpec{}, err
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: "FLOAT", Precision: prec}, nil
	case "DOUBLE":
		p.advance()
		name := "DOUBLE"
		if p.atKw("PRECISION") {
			p.advance()
			name = "DOUBLE PRECISION"
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: name}, nil
	case "DECIMAL", "DEC", "NUMERIC":
		p.advance()
		name := word
		if word == "DEC" {
			name = "DECIMAL"
		}
		ts := ast.TypeSpec{Pos: tok.Pos, Name: name}
		if p.atOp("(") {
			p.advance()
			prec, err := p.parseExpr()
			if err != nil {
				return ast.TypeSpec{}, err
			}
			ts.Precision = prec
			if p.atOp(",") {
				p.advance()
				scale, err := p.parseExpr()
				if err != nil {
					return ast.TypeSpec{}, err
				}
				ts.Scale = scale
			}
			if _, err := p.expectOp(")"); err != nil {
				return ast.TypeSpec{}, err
			}
		}
		return ts, nil
	case "CHAR", "CHARACTER":
		p.advance()
		name := "CHAR"
		if p.atKw("VARYING") {
			p.advance()
			name = "VARCHAR"
		}
		prec, err := p.parsePrecisionParen()
		if err != nil {
			return ast.TypeSpec{}, err
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: name, Precision: prec}, nil
	case "VARCHAR":
		p.advance()
		prec, err := p.parsePrecisionParen()
		if err != nil {
			return ast.TypeSpec{}, err
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: "VARCHAR", Precision: prec}, nil
	case "BINARY", "VARBINARY":
		p.advance()
		name := word
		if word == "BINARY" && p.atKw("VARYING") {
			p.advance()
			name = "VARBINARY"
		}
		prec, err := p.parsePrecisionParen()
		if err != nil {
			return ast.TypeSpec{}, err
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: name, Precision: prec}, nil
	case "DATE":
		p.advance()
		return ast.TypeSpec{Pos: tok.Pos, Name: "DATE"}, nil
	case "TIME", "TIMESTAMP":
		p.advance()
		prec, err := p.parsePrecisionParen()
		if err != nil {
			return ast.TypeSpec{}, err
		}
		name, err := p.parseTimeZoneSuffix(word)
		if err != nil {
			return ast.TypeSpec{}, err
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: name, Precision: prec}, nil
	case "INTERVAL":
		p.advance()
		start, end, err := p.parseIntervalUnits()
		if err != nil {
			return ast.TypeSpec{}, err
		}
		name := "INTERVAL " + start
		if end != "" {
			name += " TO " + end
		}
		return ast.TypeSpec{Pos: tok.Pos, Name: name}, nil
	default:
		return ast.TypeSpec{}, p.errAt(tok.Pos, "unknown type name %q", tok.Text)
	}
}

// parsePrecisionParen 解析可选的 (n) 精度括号;精度/刻度二元形态 (p[,s])
// 仅 DECIMAL 族允许,由该分支自行展开。
func (p *Parser) parsePrecisionParen() (ast.Expr, error) {
	if !p.atOp("(") {
		return nil, nil
	}
	p.advance()
	e, err := p.parseExpr()
	if err != nil {
		return nil, err
	}
	if _, err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return e, nil
}

// parseTimeZoneSuffix 解析 TIME/TIMESTAMP 的时区后缀并拼入规范化名:
// WITH [LOCAL] TIME ZONE | WITHOUT TIME ZONE(可缺省)。
func (p *Parser) parseTimeZoneSuffix(base string) (string, error) {
	switch {
	case p.atKw("WITH"):
		p.advance()
		local := false
		if p.atKw("LOCAL") {
			p.advance()
			local = true
		}
		if _, err := p.expectKw("TIME"); err != nil {
			return "", err
		}
		if _, err := p.expectKw("ZONE"); err != nil {
			return "", err
		}
		if local {
			return base + " WITH LOCAL TIME ZONE", nil
		}
		return base + " WITH TIME ZONE", nil
	case p.atKw("WITHOUT"):
		p.advance()
		if _, err := p.expectKw("TIME"); err != nil {
			return "", err
		}
		if _, err := p.expectKw("ZONE"); err != nil {
			return "", err
		}
		return base + " WITHOUT TIME ZONE", nil
	default:
		return base, nil
	}
}
