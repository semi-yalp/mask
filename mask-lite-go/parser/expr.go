package parser

import (
	"strconv"
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/lexer"
	"io.masklite/go/maskerr"
)

// parseExpr 表达式入口（OR 层）。
func (p *Parser) parseExpr() (ast.Expr, error) { return p.parseOr() }

func (p *Parser) parseOr() (ast.Expr, error) {
	l, err := p.parseAnd()
	if err != nil {
		return nil, err
	}
	for p.acceptKw("or") {
		r, err := p.parseAnd()
		if err != nil {
			return nil, err
		}
		l = &ast.Binary{Pos: l.At(), Op: ast.OpOr, L: l, R: r}
	}
	return l, nil
}

func (p *Parser) parseAnd() (ast.Expr, error) {
	l, err := p.parseNot()
	if err != nil {
		return nil, err
	}
	for p.acceptKw("and") {
		r, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		l = &ast.Binary{Pos: l.At(), Op: ast.OpAnd, L: l, R: r}
	}
	return l, nil
}

func (p *Parser) parseNot() (ast.Expr, error) {
	if p.atKw("not") && !p.peekIsPredKw() {
		// `x NOT BETWEEN/IN/LIKE` 的 NOT 由谓词层处理；这里只吃前缀 NOT
		p.next()
		x, err := p.parseNot()
		if err != nil {
			return nil, err
		}
		return &ast.Unary{Pos: x.At(), Op: ast.UnaryNot, X: x}, nil
	}
	return p.parsePredicate()
}

// peekIsPredKw 判断下一个 token 是否为谓词关键字（NOT 后面跟它们时属于
// 后缀 NOT 形态）。
func (p *Parser) peekIsPredKw() bool {
	t := p.peek()
	if t.Kind != lexer.Ident {
		return false
	}
	switch strings.ToLower(t.Text) {
	case "between", "in", "like", "ilike", "similar":
		return true
	}
	return false
}

// parsePredicate 解析比较/谓词层（可链式：IS NULL 后不能再接 IS）。
func (p *Parser) parsePredicate() (ast.Expr, error) {
	x, err := p.parseSum()
	if err != nil {
		return nil, err
	}
	return p.parsePredTail(x)
}

func (p *Parser) parsePredTail(x ast.Expr) (ast.Expr, error) {
	for {
		switch {
		case p.atKw("is"):
			p.next()
			neg := p.acceptKw("not")
			switch {
			case p.acceptKw("null"):
				x = &ast.IsPred{Pos: x.At(), X: x, Neg: neg, What: ast.IsNull}
			case p.acceptKw("true"):
				x = &ast.IsPred{Pos: x.At(), X: x, Neg: neg, What: ast.IsTrue}
			case p.acceptKw("false"):
				x = &ast.IsPred{Pos: x.At(), X: x, Neg: neg, What: ast.IsFalse}
			case p.acceptKw("unknown"):
				x = &ast.IsPred{Pos: x.At(), X: x, Neg: neg, What: ast.IsUnknown}
			case p.acceptKw("distinct"):
				if err := p.expectKw("from"); err != nil {
					return nil, err
				}
				r, err := p.parseSum()
				if err != nil {
					return nil, err
				}
				x = &ast.IsPred{Pos: x.At(), X: x, Neg: neg, What: ast.IsDistinctFrom, R: r}
			default:
				return nil, p.unexpected("NULL/TRUE/FALSE/UNKNOWN/DISTINCT")
			}
		case p.atKw("not") && p.peekIsPredKw():
			p.next()
			tail, err := p.parsePredTailNeg(x, true)
			if err != nil {
				return nil, err
			}
			x = tail
		case p.atKw("between"), p.atKw("in"), p.atKw("like"), p.atKw("ilike"), p.atKw("similar"):
			tail, err := p.parsePredTailNeg(x, false)
			if err != nil {
				return nil, err
			}
			x = tail
		default:
			op, ok := p.atCompareOp()
			if !ok {
				return x, nil
			}
			p.next()
			r, err := p.parseSum()
			if err != nil {
				return nil, err
			}
			x = &ast.Binary{Pos: x.At(), Op: op, L: x, R: r}
		}
	}
}

func (p *Parser) parsePredTailNeg(x ast.Expr, neg bool) (ast.Expr, error) {
	switch {
	case p.acceptKw("between"):
		symmetric := p.acceptKw("symmetric")
		p.acceptKw("asymmetric")
		low, err := p.parseSum()
		if err != nil {
			return nil, err
		}
		if err := p.expectKw("and"); err != nil {
			return nil, err
		}
		high, err := p.parseSum()
		if err != nil {
			return nil, err
		}
		return &ast.Between{Pos: x.At(), X: x, Low: low, High: high, Neg: neg, Symmetric: symmetric}, nil
	case p.acceptKw("in"):
		if err := p.expectOp("("); err != nil {
			return nil, err
		}
		if p.atKw("select") || p.atKw("with") || p.atKw("values") {
			q, err := p.parseQuery()
			if err != nil {
				return nil, err
			}
			q, err = p.parseOrderTailInParens(q)
			if err != nil {
				return nil, err
			}
			if err := p.expectOp(")"); err != nil {
				return nil, err
			}
			return &ast.InPred{Pos: x.At(), X: x, Neg: neg, Sub: &ast.Subquery{Pos: x.At(), Query: q}}, nil
		}
		var list []ast.Expr
		for {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			list = append(list, e)
			if !p.acceptOp(",") {
				break
			}
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		return &ast.InPred{Pos: x.At(), X: x, Neg: neg, List: list}, nil
	case p.atKw("like"):
		p.next()
		return p.parseLikeTail(x, neg, ast.LikePlain)
	case p.atKw("ilike"):
		p.next()
		return p.parseLikeTail(x, neg, ast.LikeI)
	case p.atKw("similar"):
		p.next()
		if err := p.expectKw("to"); err != nil {
			return nil, err
		}
		return p.parseLikeTail(x, neg, ast.LikeSimilar)
	default:
		return nil, p.unexpected("BETWEEN/IN/LIKE")
	}
}

func (p *Parser) parseLikeTail(x ast.Expr, neg bool, kind ast.LikeKind) (ast.Expr, error) {
	pattern, err := p.parseSum()
	if err != nil {
		return nil, err
	}
	l := &ast.Like{Pos: x.At(), X: x, Pattern: pattern, Neg: neg, Kind: kind}
	if p.acceptKw("escape") {
		e, err := p.parseSum()
		if err != nil {
			return nil, err
		}
		l.Esc = e
	}
	return l, nil
}

func (p *Parser) atCompareOp() (ast.BinaryOp, bool) {
	t := p.cur()
	if t.Kind != lexer.Op {
		return 0, false
	}
	switch t.Text {
	case "=":
		return ast.OpEq, true
	case "<>", "!=":
		return ast.OpNe, true
	case "<":
		return ast.OpLt, true
	case "<=":
		return ast.OpLe, true
	case ">":
		return ast.OpGt, true
	case ">=":
		return ast.OpGe, true
	}
	return 0, false
}

// parseSum 加减与 ||。
func (p *Parser) parseSum() (ast.Expr, error) {
	l, err := p.parseTerm()
	if err != nil {
		return nil, err
	}
	for {
		var op ast.BinaryOp
		switch {
		case p.atOp("+"):
			op = ast.OpAdd
		case p.atOp("-"):
			op = ast.OpSub
		case p.atOp("||"):
			op = ast.OpConcat
		default:
			return l, nil
		}
		p.next()
		r, err := p.parseTerm()
		if err != nil {
			return nil, err
		}
		l = &ast.Binary{Pos: l.At(), Op: op, L: l, R: r}
	}
}

// parseTerm 乘除模。
func (p *Parser) parseTerm() (ast.Expr, error) {
	l, err := p.parseFactor()
	if err != nil {
		return nil, err
	}
	for {
		var op ast.BinaryOp
		switch {
		case p.atOp("*"):
			op = ast.OpMul
		case p.atOp("/"):
			op = ast.OpDiv
		case p.atOp("%"):
			op = ast.OpMod
		default:
			return l, nil
		}
		p.next()
		r, err := p.parseFactor()
		if err != nil {
			return nil, err
		}
		l = &ast.Binary{Pos: l.At(), Op: op, L: l, R: r}
	}
}

func (p *Parser) parseFactor() (ast.Expr, error) {
	switch {
	case p.atOp("+"):
		p.next()
		x, err := p.parseFactor()
		if err != nil {
			return nil, err
		}
		return &ast.Unary{Pos: x.At(), Op: ast.UnaryPlus, X: x}, nil
	case p.atOp("-"):
		p.next()
		x, err := p.parseFactor()
		if err != nil {
			return nil, err
		}
		return &ast.Unary{Pos: x.At(), Op: ast.UnaryNeg, X: x}, nil
	}
	return p.parsePostfix()
}

// parsePostfix primary 后缀：:: 类型转换、COLLATE。
func (p *Parser) parsePostfix() (ast.Expr, error) {
	x, err := p.parsePrimary()
	if err != nil {
		return nil, err
	}
	for {
		switch {
		case p.atOp("::"):
			castTok := p.cur()
			p.next()
			if p.atKw("interval") {
				// 'str'::interval：仅字符串字面量走解析期规范化；typmod/
				// 字段范围形态（::interval(3)、::interval day）拒绝——
				// 否则尾随 unit 会被静默当成列别名
				p.next()
				if p.atOp("(") || p.curIsIntervalUnitToken() {
					return nil, p.intervalCastError(castTok)
				}
				norm, err := p.normalizeStringToInterval(x, castTok)
				if err != nil {
					return nil, err
				}
				x = norm
				continue
			}
			typ, err := p.parseType()
			if err != nil {
				return nil, err
			}
			x = &ast.Cast{Pos: x.At(), X: x, Type: typ}
		case p.atKw("collate"):
			p.next()
			c, err := p.expectIdentPart()
			if err != nil {
				return nil, err
			}
			x = &ast.Collate{Pos: x.At(), X: x, Collation: c}
		default:
			return x, nil
		}
	}
}

func (p *Parser) parsePrimary() (ast.Expr, error) {
	t := p.cur()
	switch {
	case t.Kind == lexer.Number:
		p.next()
		return numberLiteral(t), nil
	case t.Kind == lexer.String:
		p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitString, Text: t.Text}, nil
	case t.Kind == lexer.Param:
		p.next()
		if len(t.Text) > 1 {
			n, _ := strconv.Atoi(t.Text[1:])
			return &ast.Param{Pos: posOf(t), Index: n}, nil
		}
		return &ast.Param{Pos: posOf(t), Index: -1}, nil
	case t.Kind == lexer.Op && t.Text == "(":
		p.next()
		if p.atKw("select") || p.atKw("with") || p.atKw("values") {
			q, err := p.parseQuery()
			if err != nil {
				return nil, err
			}
			q, err = p.parseOrderTailInParens(q)
			if err != nil {
				return nil, err
			}
			if err := p.expectOp(")"); err != nil {
				return nil, err
			}
			return &ast.Subquery{Pos: posOf(t), Query: q}, nil
		}
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		return e, nil
	case p.atKw("null"):
		p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitNull}, nil
	case p.atKw("true"):
		p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitTrue}, nil
	case p.atKw("false"):
		p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitFalse}, nil
	case p.atKw("date") && p.peek().Kind == lexer.String:
		p.next()
		s := p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitDate, Text: s.Text}, nil
	case p.atKw("time") && p.peek().Kind == lexer.String:
		p.next()
		s := p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitTime, Text: s.Text}, nil
	case p.atKw("timestamp") && p.peek().Kind == lexer.String:
		p.next()
		s := p.next()
		return &ast.Literal{Pos: posOf(t), Kind: ast.LitTimestamp, Text: s.Text}, nil
	case p.atKw("interval"):
		return p.parseInterval()
	case p.atKw("case"):
		return p.parseCase()
	case p.atKw("cast"):
		p.next()
		if err := p.expectOp("("); err != nil {
			return nil, err
		}
		x, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if err := p.expectKw("as"); err != nil {
			return nil, err
		}
		typ, err := p.parseType()
		if err != nil {
			return nil, err
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		if typ.Name == "INTERVAL" {
			// CAST('str' AS INTERVAL)：与 ::interval 同语义，仅字符串字面量
			norm, err := p.normalizeStringToInterval(x, t)
			if err != nil {
				return nil, err
			}
			return norm, nil
		}
		return &ast.Cast{Pos: posOf(t), X: x, Type: typ}, nil
	case p.atKw("exists"):
		p.next()
		if err := p.expectOp("("); err != nil {
			return nil, err
		}
		q, err := p.parseQuery()
		if err != nil {
			return nil, err
		}
		q, err = p.parseOrderTailInParens(q)
		if err != nil {
			return nil, err
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		return &ast.Exists{Pos: posOf(t), Query: q}, nil
	case p.atKw("substring"):
		p.next()
		args, err := p.parseSubstringArgs()
		if err != nil {
			return nil, err
		}
		return &ast.Call{Pos: posOf(t), Name: ast.IdentPart{Value: "substring"}, Args: args}, nil
	case p.atKw("trim"):
		return p.parseTrim()
	case t.Kind == lexer.Ident && (reserved[strings.ToLower(t.Text)] && !funcWords[strings.ToLower(t.Text)] || reserved[strings.ToLower(t.Text)] && !p.peekIsLparen()):
		return nil, p.unexpected("an expression")
	case t.Kind == lexer.Ident || t.Kind == lexer.QuotedIdent:
		return p.parseIdentExpr()
	default:
		return nil, p.unexpected("an expression")
	}
}

// curIsIntervalUnitToken 对齐 Java ::interval 分支的尾随拒绝集：
// 年/季/月/周/日/时/分/秒单位词（含复数）或精度括号。
func (p *Parser) curIsIntervalUnitToken() bool {
	if p.atOp("(") {
		return true
	}
	if p.cur().Kind != lexer.Ident {
		return false
	}
	switch strings.ToLower(p.cur().Text) {
	case "year", "years", "quarter", "quarters", "month", "months",
		"week", "weeks", "day", "days", "hour", "hours",
		"minute", "minutes", "second", "seconds":
		return true
	}
	return false
}

// normalizeStringToInterval 把字符串字面量操作数规范化为 INTERVAL 字面量；
// 非字面量或不支持形态 → PARSE_ERROR（fail-closed）。
func (p *Parser) normalizeStringToInterval(x ast.Expr, pos lexer.Token) (ast.Expr, error) {
	sl, ok := x.(*ast.Literal)
	if !ok || sl.Kind != ast.LitString {
		return nil, p.intervalCastError(pos)
	}
	norm, ok := normalizeBareInterval(sl.Text, 1)
	if !ok {
		return nil, p.intervalCastError(pos)
	}
	return &ast.Literal{Pos: x.At(), Kind: ast.LitInterval,
		Text: norm.Value, Unit: norm.Unit, UnitTo: norm.UnitTo}, nil
}

func (p *Parser) peekIsLparen() bool {
	t := p.peek()
	return t.Kind == lexer.Op && t.Text == "("
}

// parseIdentExpr 标识符 / 多段名 / 函数调用。
func (p *Parser) parseIdentExpr() (ast.Expr, error) {
	t := p.cur()
	first := p.foldIdent(t)
	p.next()
	parts := []ast.IdentPart{first}
	for p.atOp(".") && p.peekIsIdent() {
		p.next()
		part, err := p.expectIdentPart()
		if err != nil {
			return nil, err
		}
		parts = append(parts, part)
	}
	if p.atOp("(") {
		p.next()
		call := &ast.Call{Pos: posOf(t), Name: first}
		if p.atOp("*") {
			p.next()
			call.Star = true
		} else if p.acceptKw("distinct") {
			call.Distinct = true
			if err := p.parseCallArgs(call); err != nil {
				return nil, err
			}
		} else {
			p.acceptKw("all")
			if err := p.parseCallArgs(call); err != nil {
				return nil, err
			}
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		if p.atKw("over") {
			p.next()
			w, err := p.parseWindow()
			if err != nil {
				return nil, err
			}
			call.Over = w
		}
		return call, nil
	}
	return &ast.Ident{Pos: posOf(t), Parts: parts}, nil
}

func (p *Parser) parseCallArgs(call *ast.Call) error {
	if p.atOp(")") {
		return nil
	}
	for {
		e, err := p.parseExpr()
		if err != nil {
			return err
		}
		call.Args = append(call.Args, e)
		if !p.acceptOp(",") {
			return nil
		}
	}
}

// parseSubstringArgs 解析 SUBSTRING(x FROM a [FOR b]) 与 SUBSTRING(x, a[, b])，
// 统一为位置参数（渲染与 Calcite 一致：SUBSTRING(x, a, b)）。
func (p *Parser) parseSubstringArgs() ([]ast.Expr, error) {
	if err := p.expectOp("("); err != nil {
		return nil, err
	}
	x, err := p.parseExpr()
	if err != nil {
		return nil, err
	}
	args := []ast.Expr{x}
	if p.acceptKw("from") {
		a, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		args = append(args, a)
		if p.acceptKw("for") {
			b, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			args = append(args, b)
		}
	} else {
		for p.acceptOp(",") {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			args = append(args, e)
		}
	}
	if err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return args, nil
}

// parseTrim 解析 TRIM([BOTH|LEADING|TRAILING] [x] FROM y) 与 TRIM(x)。
func (p *Parser) parseTrim() (ast.Expr, error) {
	t := p.cur()
	p.next()
	if err := p.expectOp("("); err != nil {
		return nil, err
	}
	mode := ""
	if p.acceptKw("both") {
		mode = "both"
	} else if p.acceptKw("leading") {
		mode = "leading"
	} else if p.acceptKw("trailing") {
		mode = "trailing"
	}
	var args []ast.Expr
	if mode != "" {
		if !p.atKw("from") {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			args = append(args, e)
		}
		if err := p.expectKw("from"); err != nil {
			return nil, err
		}
		y, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		args = append(args, y)
	} else {
		x, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		args = append(args, x)
		if p.acceptKw("from") {
			y, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			args = append(args, y)
		}
	}
	if err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return &ast.Call{Pos: posOf(t), Name: ast.IdentPart{Value: "trim"}, Args: args}, nil
}

func (p *Parser) parseInterval() (ast.Expr, error) {
	t := p.cur()
	p.next() // INTERVAL
	sign := 1
	if p.acceptOp("-") {
		sign = -1
	} else {
		p.acceptOp("+")
	}
	if p.cur().Kind != lexer.String {
		return nil, p.unexpected("a string literal")
	}
	s := p.next()
	lit := &ast.Literal{Pos: posOf(t), Kind: ast.LitInterval, Text: s.Text}
	// LOOKAHEAD(2)：紧邻单位词 → 限定词形式；否则裸串规范化
	if p.cur().Kind == lexer.Ident && p.isIntervalUnitWord(p.cur().Text) {
		unit, err := p.expectIdentPart()
		if err != nil {
			return nil, err
		}
		lit.Unit = strings.ToUpper(unit.Value)
		if p.acceptKw("to") {
			u2, err := p.expectIdentPart()
			if err != nil {
				return nil, err
			}
			lit.UnitTo = strings.ToUpper(u2.Value)
		}
		if sign == -1 {
			lit.Text = "-" + lit.Text
		}
		return lit, nil
	}
	// 裸形式：解析期规范化为等值限定词字面量（不支持形态 → PARSE_ERROR）
	norm, ok := normalizeBareInterval(s.Text, sign)
	if !ok {
		return nil, maskerr.Errorf(maskerr.ParseError,
			"unsupported interval literal '%s' at line %d, column %d", s.Text, t.Pos.Line, t.Pos.Column)
	}
	lit.Text = norm.Value
	lit.Unit = norm.Unit
	lit.UnitTo = norm.UnitTo
	return lit, nil
}

// isIntervalUnitWord 判断是否为 interval 限定词单位（YEAR/MONTH/DAY/HOUR/
// MINUTE/SECOND 及复数）。
func (p *Parser) isIntervalUnitWord(word string) bool {
	switch strings.ToLower(word) {
	case "year", "years", "month", "months", "day", "days", "hour", "hours",
		"minute", "minutes", "second", "seconds":
		return true
	}
	return false
}

// intervalCastError 是 interval 转换/规范化失败的统一 PARSE_ERROR。
func (p *Parser) intervalCastError(pos lexer.Token) error {
	return maskerr.Errorf(maskerr.ParseError,
		"unsupported interval cast at line %d, column %d: only string literals "+
			"with supported bare interval forms can be cast to INTERVAL",
		pos.Pos.Line, pos.Pos.Column)
}

func (p *Parser) parseCase() (ast.Expr, error) {
	t := p.cur()
	p.next() // CASE
	c := &ast.Case{Pos: posOf(t)}
	if !p.atKw("when") {
		operand, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		c.Operand = operand
	}
	for p.acceptKw("when") {
		cond, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if err := p.expectKw("then"); err != nil {
			return nil, err
		}
		then, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		c.Whens = append(c.Whens, ast.When{Cond: cond, Then: then})
	}
	if len(c.Whens) == 0 {
		return nil, p.unexpected("WHEN")
	}
	if p.acceptKw("else") {
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		c.Else = e
	}
	if err := p.expectKw("end"); err != nil {
		return nil, err
	}
	return c, nil
}

func (p *Parser) parseWindow() (*ast.Window, error) {
	start := p.cur()
	if err := p.expectOp("("); err != nil {
		return nil, err
	}
	w := &ast.Window{Pos: posOf(start)}
	if p.acceptKw("partition") {
		if err := p.expectKw("by"); err != nil {
			return nil, err
		}
		for {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			w.PartitionBy = append(w.PartitionBy, e)
			if !p.acceptOp(",") {
				break
			}
		}
	}
	if p.acceptKw("order") {
		if err := p.expectKw("by"); err != nil {
			return nil, err
		}
		items, err := p.parseOrderItems()
		if err != nil {
			return nil, err
		}
		w.Order = items
	}
	if p.atKw("rows") || p.atKw("range") {
		f, err := p.parseFrame()
		if err != nil {
			return nil, err
		}
		w.Frame = f
	}
	if err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return w, nil
}

func (p *Parser) parseFrame() (*ast.Frame, error) {
	start := p.cur()
	f := &ast.Frame{Pos: posOf(start)}
	if p.acceptKw("rows") {
		f.Unit = ast.FrameRows
	} else if p.acceptKw("range") {
		f.Unit = ast.FrameRange
	} else {
		return nil, p.unexpected("ROWS or RANGE")
	}
	if p.acceptKw("between") {
		b, err := p.parseFrameBound()
		if err != nil {
			return nil, err
		}
		f.Start = b
		if err := p.expectKw("and"); err != nil {
			return nil, err
		}
		e, err := p.parseFrameBound()
		if err != nil {
			return nil, err
		}
		f.End = e
		f.HasEnd = true
	} else {
		b, err := p.parseFrameBound()
		if err != nil {
			return nil, err
		}
		f.Start = b
	}
	return f, nil
}

func (p *Parser) parseFrameBound() (ast.FrameBound, error) {
	switch {
	case p.atKw("unbounded"):
		p.next()
		if p.acceptKw("preceding") {
			return ast.FrameBound{Kind: ast.BoundUnboundedPreceding}, nil
		}
		if p.acceptKw("following") {
			return ast.FrameBound{Kind: ast.BoundUnboundedFollowing}, nil
		}
		return ast.FrameBound{}, p.unexpected("PRECEDING or FOLLOWING")
	case p.atKw("current"):
		p.next()
		if err := p.expectKw("row"); err != nil {
			return ast.FrameBound{}, err
		}
		return ast.FrameBound{Kind: ast.BoundCurrentRow}, nil
	default:
		e, err := p.parseSum()
		if err != nil {
			return ast.FrameBound{}, err
		}
		if p.acceptKw("preceding") {
			return ast.FrameBound{Kind: ast.BoundPreceding, Offset: e}, nil
		}
		if p.acceptKw("following") {
			return ast.FrameBound{Kind: ast.BoundFollowing, Offset: e}, nil
		}
		return ast.FrameBound{}, p.unexpected("PRECEDING or FOLLOWING")
	}
}

// ---------------- 类型 ----------------

// parseType 解析 CAST 类型名（规范大写名对齐 Calcite 渲染）。
func (p *Parser) parseType() (ast.TypeSpec, error) {
	t := p.cur()
	if t.Kind != lexer.Ident && t.Kind != lexer.QuotedIdent {
		return ast.TypeSpec{}, p.unexpected("a type name")
	}
	word := strings.ToLower(t.Text)
	spec := ast.TypeSpec{Pos: posOf(t)}
	switch word {
	case "boolean", "bool":
		spec.Name = "BOOLEAN"
		p.next()
	case "tinyint":
		spec.Name = "TINYINT"
		p.next()
	case "smallint", "int2":
		spec.Name = "SMALLINT"
		p.next()
	case "int", "integer", "int4":
		spec.Name = "INTEGER"
		p.next()
	case "bigint", "int8":
		spec.Name = "BIGINT"
		p.next()
	case "real", "float4":
		spec.Name = "REAL"
		p.next()
	case "float":
		p.next()
		spec.Name = "FLOAT"
		if p.atOp("(") {
			if err := p.parseTypeParams(&spec, 1); err != nil {
				return spec, err
			}
		}
	case "double":
		p.next()
		p.acceptKw("precision")
		spec.Name = "DOUBLE"
	case "decimal", "dec", "numeric":
		p.next()
		spec.Name = "DECIMAL"
		if p.atOp("(") {
			if err := p.parseTypeParams(&spec, 2); err != nil {
				return spec, err
			}
		}
	case "char", "character":
		p.next()
		if p.acceptKw("varying") {
			spec.Name = "VARCHAR"
		} else {
			spec.Name = "CHAR"
		}
		if p.atOp("(") {
			if err := p.parseTypeParams(&spec, 1); err != nil {
				return spec, err
			}
		}
	case "varchar":
		p.next()
		spec.Name = "VARCHAR"
		if p.atOp("(") {
			if err := p.parseTypeParams(&spec, 1); err != nil {
				return spec, err
			}
		}
	case "text":
		spec.Name = "TEXT"
		p.next()
	case "date":
		spec.Name = "DATE"
		p.next()
	case "interval":
		// 仅支持无 typmod/字段范围的纯 INTERVAL（CAST('str' AS INTERVAL)
		// 走字符串规范化分支）；精度括号与字段范围形态拒绝
		spec.Name = "INTERVAL"
		p.next()
		if p.curIsIntervalUnitToken() {
			return ast.TypeSpec{}, p.intervalCastError(t)
		}
	case "time":
		p.next()
		spec.Name = "TIME"
		if p.atOp("(") {
			if err := p.parseTypeParams(&spec, 1); err != nil {
				return spec, err
			}
		}
		if err := p.parseTZSuffix(&spec); err != nil {
			return spec, err
		}
	case "timestamp":
		p.next()
		spec.Name = "TIMESTAMP"
		if p.atOp("(") {
			if err := p.parseTypeParams(&spec, 1); err != nil {
				return spec, err
			}
		}
		if err := p.parseTZSuffix(&spec); err != nil {
			return spec, err
		}
	default:
		return ast.TypeSpec{}, p.unexpected("a type name")
	}
	return spec, nil
}

func (p *Parser) parseTypeParams(spec *ast.TypeSpec, max int) error {
	p.next() // (
	if p.cur().Kind != lexer.Number {
		return p.unexpected("a number")
	}
	prec, err := strconv.Atoi(p.cur().Text)
	if err != nil {
		return p.unexpected("a number")
	}
	p.next()
	spec.Precision = &prec
	if max > 1 && p.acceptOp(",") {
		if p.cur().Kind != lexer.Number {
			return p.unexpected("a number")
		}
		scale, err := strconv.Atoi(p.cur().Text)
		if err != nil {
			return p.unexpected("a number")
		}
		p.next()
		spec.Scale = &scale
	}
	return p.expectOp(")")
}

func (p *Parser) parseTZSuffix(spec *ast.TypeSpec) error {
	if p.acceptKw("with") {
		if err := p.expectKw("time"); err != nil {
			return err
		}
		if err := p.expectKw("zone"); err != nil {
			return err
		}
		spec.Suffix = "WITH TIME ZONE"
	} else if p.atKw("without") {
		p.next()
		if err := p.expectKw("time"); err != nil {
			return err
		}
		if err := p.expectKw("zone"); err != nil {
			return err
		}
		spec.Suffix = "WITHOUT TIME ZONE"
	}
	return nil
}

// numberLiteral 把数字 token 分类为 Int/Decimal/Approx。
func numberLiteral(t lexer.Token) *ast.Literal {
	text := t.Text
	kind := ast.LitInt
	if strings.ContainsAny(text, ".eE") {
		kind = ast.LitDecimal
		if strings.ContainsAny(text, "eE") {
			kind = ast.LitApprox
		}
	}
	return &ast.Literal{Pos: posOf(t), Kind: kind, Text: text}
}

// peekAt2 取 cur+2 的 token（t.* 判定用）。
func (p *Parser) peekAt2() lexer.Token {
	if p.i+2 < len(p.toks) {
		return p.toks[p.i+2]
	}
	return p.toks[len(p.toks)-1]
}
