// Package parser 实现 PostgreSQL 单方言的递归下降解析器，语法面以
// TPC-DS 99 语料 + MaskLiteTest 用例为验收基准：SELECT/WITH/集合运算/
// VALUES、顶层与括号内 ORDER BY/LIMIT/OFFSET/FETCH、JOIN/派生表/子查询、
// 窗口函数、ROLLUP、完整表达式链。错误为 maskerr.PARSE_ERROR。
package parser

import (
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/dialect"
	"io.masklite/go/lexer"
	"io.masklite/go/maskerr"
)

// Parser 持有 token 流；每次 ParseStatement 解析一条语句（须到 EOF）。
type Parser struct {
	prof *dialect.Profile
	toks []lexer.Token
	i    int
}

// New 先做词法分析。
func New(p *dialect.Profile, src string) (*Parser, error) {
	toks, err := lexer.Lex(p, src)
	if err != nil {
		return nil, err
	}
	return &Parser{prof: p, toks: toks}, nil
}

// ParseStatement 解析单条语句；语句结束后必须到 EOF（允许多余分号）。
func (p *Parser) ParseStatement() (ast.Statement, error) {
	stmt, err := p.parseStatementInner()
	if err != nil {
		return nil, err
	}
	// Unsupported 标记语句不解析其余部分（只读内核只关心 kind）
	if _, unsupported := stmt.(*ast.Unsupported); unsupported {
		p.i = len(p.toks) - 1
		return stmt, nil
	}
	// 允许尾部分号（CLI --sql 手写场景）
	for p.atOp(";") {
		p.next()
	}
	if p.cur().Kind != lexer.EOF {
		return nil, p.unexpected("end of statement")
	}
	return stmt, nil
}

func (p *Parser) parseStatementInner() (ast.Statement, error) {
	t := p.cur()
	if t.Kind == lexer.EOF {
		return nil, p.unexpected("a statement")
	}
	if t.Kind == lexer.Op && t.Text == "(" {
		// 语句级括号查询：内容是完整查询（可继续集合运算/尾子句）
		q, err := p.parseQuery()
		if err != nil {
			return nil, err
		}
		return p.parseOrderTail(q)
	}
	if t.Kind == lexer.Ident {
		switch {
		case p.atKw("select"), p.atKw("values"), p.atKw("with"):
			q, err := p.parseQuery()
			if err != nil {
				return nil, err
			}
			return p.parseOrderTail(q)
		case p.atKw("insert"):
			// 只读内核：INSERT 语法认识但不放行（对齐 Java：解析期放行、
			// 只读门拒绝，错误码 UNSUPPORTED_STATEMENT）
			if !p.peekIsKw("into") {
				return nil, p.unexpected("INTO")
			}
			return &ast.Unsupported{Pos: posOf(t), KindLowerN: "insert", KindUpperN: "INSERT", Gate: true}, nil
		case p.atKw("create"):
			if !p.peekIsKw("table") {
				return nil, p.unexpected("TABLE")
			}
			return &ast.Unsupported{Pos: posOf(t), KindLowerN: "create_table", KindUpperN: "CREATE_TABLE", Gate: true}, nil
		case p.atKw("update"), p.atKw("delete"), p.atKw("merge"), p.atKw("grant"),
			p.atKw("revoke"), p.atKw("drop"), p.atKw("alter"), p.atKw("truncate"),
			p.atKw("explain"), p.atKw("call"), p.atKw("set"), p.atKw("show"),
			p.atKw("begin"), p.atKw("commit"), p.atKw("rollback"), p.atKw("use"),
			p.atKw("describe"), p.atKw("execute"):
			return &ast.Unsupported{Pos: posOf(t),
				KindLowerN: strings.ToUpper(t.Text), KindUpperN: strings.ToUpper(t.Text)}, nil
		}
	}
	return nil, p.unexpected("a statement")
}

// ---------------- 查询级 ----------------

// parseQuery 解析集合运算级查询：UNION/EXCEPT 同级左结合，INTERSECT 更紧。
func (p *Parser) parseQuery() (ast.Query, error) {
	left, err := p.parseQueryTerm()
	if err != nil {
		return nil, err
	}
	for {
		var op ast.SetOpKind
		switch {
		case p.atKw("union"):
			op = ast.OpUnion
		case p.atKw("except"):
			op = ast.OpExcept
		default:
			return left, nil
		}
		p.next()
		all, err := p.parseSetQuantifier()
		if err != nil {
			return nil, err
		}
		right, err := p.parseQueryTerm()
		if err != nil {
			return nil, err
		}
		left = &ast.SetOp{Pos: left.At(), Op: op, All: all, Left: left, Right: right}
	}
}

func (p *Parser) parseQueryTerm() (ast.Query, error) {
	left, err := p.parsePrimaryQuery()
	if err != nil {
		return nil, err
	}
	for p.atKw("intersect") {
		p.next()
		all, err := p.parseSetQuantifier()
		if err != nil {
			return nil, err
		}
		right, err := p.parsePrimaryQuery()
		if err != nil {
			return nil, err
		}
		left = &ast.SetOp{Pos: left.At(), Op: ast.OpIntersect, All: all, Left: left, Right: right}
	}
	return left, nil
}

func (p *Parser) parseSetQuantifier() (bool, error) {
	if p.acceptKw("all") {
		return true, nil
	}
	p.acceptKw("distinct")
	return false, nil
}

func (p *Parser) parsePrimaryQuery() (ast.Query, error) {
	t := p.cur()
	switch {
	case p.atKw("select"):
		return p.parseSelect()
	case p.atKw("values"):
		return p.parseValues()
	case p.atKw("with"):
		return p.parseWith()
	case t.Kind == lexer.Op && t.Text == "(":
		return p.parseParenQuery()
	default:
		return nil, p.unexpected("a query")
	}
}

// parseParenQuery 解析括号查询：内部是完整查询（集合运算、再嵌括号）+
// 可选 ORDER BY/LIMIT 尾。
func (p *Parser) parseParenQuery() (ast.Query, error) {
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
	return q, nil
}

// parenContentIsQueryFrom 判别括号内容（从 start 开始，外层 ( 已消费）
// 是查询还是括号化的 JOIN 链/裸引用：深度 0 处遇到 SELECT/WITH/VALUES 或
// 集合运算关键字 → 查询；遇到 JOIN 或内容收口 → JOIN 链。
func (p *Parser) parenContentIsQueryFrom(start int) bool {
	depth := 0
	for i := start; i < len(p.toks); i++ {
		t := p.toks[i]
		if t.Kind == lexer.Op {
			switch t.Text {
			case "(":
				depth++
				continue
			case ")":
				if depth == 0 {
					return false
				}
				depth--
				continue
			}
		}
		if depth == 0 && t.Kind == lexer.Ident {
			switch strings.ToLower(t.Text) {
			case "select", "with", "values", "union", "intersect", "except":
				return true
			case "join":
				return false
			}
		}
	}
	return true
}

// parseOrderTail 语句级尾子句：ORDER BY / LIMIT / OFFSET / FETCH →
// ast.OrderBy 包装；没有尾子句时原样返回。
func (p *Parser) parseOrderTail(q ast.Query) (ast.Statement, error) {
	ob, changed, err := p.parseTail(q)
	if err != nil {
		return nil, err
	}
	if !changed {
		return q.(ast.Statement), nil
	}
	return ob, nil
}

// parseOrderTailInParens 括号内的尾子句：结果作为查询节点。
func (p *Parser) parseOrderTailInParens(q ast.Query) (ast.Query, error) {
	ob, changed, err := p.parseTail(q)
	if err != nil {
		return nil, err
	}
	if !changed {
		return q, nil
	}
	return ob, nil
}

func (p *Parser) parseTail(q ast.Query) (*ast.OrderBy, bool, error) {
	var items []ast.OrderItem
	var limit, offset, fetch ast.Expr
	limitAll := false
	changed := false
	if p.acceptKw("order") {
		if err := p.expectKw("by"); err != nil {
			return nil, false, err
		}
		its, err := p.parseOrderItems()
		if err != nil {
			return nil, false, err
		}
		items = its
		changed = true
	}
	hasLimit := p.atKw("limit")
	hasFetch := p.atKw("fetch")
	if hasLimit && hasFetch {
		return nil, false, maskerr.New(maskerr.ParseError,
			"FETCH cannot be combined with LIMIT")
	}
	if p.acceptKw("limit") {
		if p.acceptKw("all") {
			limitAll = true
		} else {
			e, err := p.parseSum()
			if err != nil {
				return nil, false, err
			}
			limit = e
		}
		changed = true
		if p.atKw("fetch") {
			return nil, false, maskerr.New(maskerr.ParseError,
				"FETCH cannot be combined with LIMIT")
		}
	}
	if p.acceptKw("offset") {
		e, err := p.parseSum()
		if err != nil {
			return nil, false, err
		}
		offset = e
		p.acceptKw("row")
		p.acceptKw("rows")
		changed = true
	}
	if p.acceptKw("fetch") {
		if !p.acceptKw("first") && !p.acceptKw("next") {
			return nil, false, p.unexpected("FIRST or NEXT")
		}
		e, err := p.parseSum()
		if err != nil {
			return nil, false, err
		}
		fetch = e
		p.acceptKw("row")
		p.acceptKw("rows")
		if err := p.expectKw("only"); err != nil {
			return nil, false, err
		}
		changed = true
	}
	if !changed {
		return nil, false, nil
	}
	return &ast.OrderBy{Pos: q.At(), Query: q, Items: items,
		Limit: limit, LimitAll: limitAll, Offset: offset, Fetch: fetch}, true, nil
}

// parseOrderItems 解析 ORDER BY 项列表。
func (p *Parser) parseOrderItems() ([]ast.OrderItem, error) {
	var items []ast.OrderItem
	for {
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		item := ast.OrderItem{Expr: e}
		if p.acceptKw("asc") {
			item.Dir = ast.DirAsc
		} else if p.acceptKw("desc") {
			item.Dir = ast.DirDesc
		}
		if p.acceptKw("nulls") {
			if p.acceptKw("first") {
				item.Nulls = ast.NullsFirst
			} else if p.acceptKw("last") {
				item.Nulls = ast.NullsLast
			} else {
				return nil, p.unexpected("FIRST or LAST")
			}
		}
		items = append(items, item)
		if !p.acceptOp(",") {
			return items, nil
		}
	}
}

// ---------------- WITH / VALUES ----------------

func (p *Parser) parseWith() (ast.Query, error) {
	start := p.cur()
	p.next() // WITH
	recursive := p.acceptKw("recursive")
	var items []ast.WithItem
	for {
		name, err := p.expectIdentPart()
		if err != nil {
			return nil, err
		}
		item := ast.WithItem{Pos: posOf(start), Name: name}
		if p.atOp("(") && p.peekIsIdent() {
			cols, err := p.parseIdentList()
			if err != nil {
				return nil, err
			}
			item.Columns = cols
		}
		if err := p.expectKw("as"); err != nil {
			return nil, err
		}
		if err := p.expectOp("("); err != nil {
			return nil, err
		}
		body, err := p.parseQuery()
		if err != nil {
			return nil, err
		}
		body, err = p.parseOrderTailInParens(body)
		if err != nil {
			return nil, err
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		item.Body = body
		items = append(items, item)
		if !p.acceptOp(",") {
			break
		}
	}
	body, err := p.parseQuery()
	if err != nil {
		return nil, err
	}
	return &ast.With{Pos: posOf(start), Recursive: recursive, Items: items, Body: body}, nil
}

func (p *Parser) parseValues() (ast.Query, error) {
	start := p.cur()
	p.next() // VALUES
	var rows [][]ast.Expr
	for {
		if err := p.expectOp("("); err != nil {
			return nil, err
		}
		var row []ast.Expr
		for {
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			row = append(row, e)
			if !p.acceptOp(",") {
				break
			}
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		rows = append(rows, row)
		if !p.acceptOp(",") {
			break
		}
	}
	return &ast.Values{Pos: posOf(start), Rows: rows}, nil
}

// ---------------- token 助手 ----------------

func (p *Parser) cur() lexer.Token  { return p.toks[p.i] }
func (p *Parser) next() lexer.Token {
	t := p.toks[p.i]
	if p.i < len(p.toks)-1 {
		p.i++
	}
	return t
}

func (p *Parser) peek() lexer.Token {
	if p.i+1 < len(p.toks) {
		return p.toks[p.i+1]
	}
	return p.toks[len(p.toks)-1]
}

func (p *Parser) atOp(op string) bool {
	t := p.cur()
	return t.Kind == lexer.Op && t.Text == op
}

func (p *Parser) acceptOp(op string) bool {
	if p.atOp(op) {
		p.next()
		return true
	}
	return false
}

func (p *Parser) expectOp(op string) error {
	if !p.acceptOp(op) {
		return p.unexpected("'" + op + "'")
	}
	return nil
}

// atKw 判断当前 token 是否为未引号关键字（大小写不敏感）。
func (p *Parser) atKw(word string) bool {
	t := p.cur()
	return t.Kind == lexer.Ident && strings.EqualFold(t.Text, word)
}

func (p *Parser) peekIsKw(word string) bool {
	t := p.peek()
	return t.Kind == lexer.Ident && strings.EqualFold(t.Text, word)
}

func (p *Parser) acceptKw(word string) bool {
	if p.atKw(word) {
		p.next()
		return true
	}
	return false
}

func (p *Parser) expectKw(word string) error {
	if !p.acceptKw(word) {
		return p.unexpected("'" + strings.ToUpper(word) + "'")
	}
	return nil
}

func (p *Parser) peekIsIdent() bool {
	t := p.peek()
	return t.Kind == lexer.Ident || t.Kind == lexer.QuotedIdent
}

// expectIdentPart 取一个标识符并按方言折算大小写。
func (p *Parser) expectIdentPart() (ast.IdentPart, error) {
	t := p.cur()
	if t.Kind != lexer.Ident && t.Kind != lexer.QuotedIdent {
		return ast.IdentPart{}, p.unexpected("an identifier")
	}
	p.next()
	return p.foldIdent(t), nil
}

// foldIdent 按方言折算：未引号 → 小写（PG），引号内原样。
func (p *Parser) foldIdent(t lexer.Token) ast.IdentPart {
	if t.Kind == lexer.QuotedIdent {
		return ast.IdentPart{Value: t.Text, Quoted: true}
	}
	return ast.IdentPart{Value: strings.ToLower(t.Text)}
}

func posOf(t lexer.Token) ast.Pos {
	return ast.Pos{Line: t.Pos.Line, Column: t.Pos.Column}
}

// unexpected 生成 Calcite 风格的语法错误。
func (p *Parser) unexpected(expected string) error {
	t := p.cur()
	got := t.Text
	switch t.Kind {
	case lexer.EOF:
		got = "<EOF>"
	case lexer.String:
		got = "'" + t.Text + "'"
	case lexer.QuotedIdent:
		got = "\"" + t.Text + "\""
	}
	return maskerr.Errorf(maskerr.ParseError,
		"Encountered \"%s\" at line %d, column %d; expected %s",
		got, t.Pos.Line, t.Pos.Column, expected)
}
