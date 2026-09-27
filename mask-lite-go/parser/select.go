package parser

import (
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/lexer"
)

// reserved 别名/表达式阻断词表（小写）。出现在这些位置的关键字不能当作
// 别名或表达式起始；`left`/`right`/`replace` 例外（后跟 ( 时是函数）。
var reserved = map[string]bool{
	"select": true, "from": true, "where": true, "group": true, "having": true,
	"order": true, "limit": true, "offset": true, "fetch": true,
	"union": true, "intersect": true, "except": true,
	"as": true, "on": true, "using": true, "join": true,
	"inner": true, "left": true, "right": true, "full": true, "outer": true,
	"cross": true, "natural": true, "asc": true, "desc": true, "nulls": true,
	"partition": true, "by": true, "rows": true, "range": true,
	"unbounded": true, "preceding": true, "following": true, "current": true,
	"row": true, "when": true, "then": true, "else": true, "end": true,
	"and": true, "or": true, "not": true, "in": true, "is": true,
	"null": true, "like": true, "ilike": true, "similar": true,
	"between": true, "exists": true, "distinct": true, "all": true,
	"over": true, "with": true, "values": true, "case": true, "cast": true,
	"window": true, "into": true, "set": true, "grouping": true, "sets": true,
	"rollup": true, "cube": true, "escape": true, "to": true, "both": true,
	"leading": true, "trailing": true,
}

// funcWords 允许作为函数名调用（后跟 ( 时豁免 reserved 阻断）。
var funcWords = map[string]bool{"left": true, "right": true, "replace": true, "grouping": true}

// aliasStart 判断当前 token 能否开始一个别名。
func (p *Parser) aliasStart() bool {
	t := p.cur()
	if t.Kind == lexer.QuotedIdent {
		return true
	}
	return t.Kind == lexer.Ident && !reserved[strings.ToLower(t.Text)]
}

// ---------------- SELECT ----------------

func (p *Parser) parseSelect() (ast.Query, error) {
	start := p.cur()
	if err := p.expectKw("select"); err != nil {
		return nil, err
	}
	sel := &ast.Select{Pos: posOf(start)}
	if p.acceptKw("distinct") {
		sel.Distinct = true
	} else {
		p.acceptKw("all")
	}
	for {
		item, err := p.parseSelectItem()
		if err != nil {
			return nil, err
		}
		sel.Items = append(sel.Items, item)
		if !p.acceptOp(",") {
			break
		}
	}
	if p.acceptKw("from") {
		refs, err := p.parseFromList()
		if err != nil {
			return nil, err
		}
		sel.From = refs
	}
	if p.acceptKw("where") {
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		sel.Where = e
	}
	if p.acceptKw("group") {
		if err := p.expectKw("by"); err != nil {
			return nil, err
		}
		items, err := p.parseGroupItems()
		if err != nil {
			return nil, err
		}
		sel.GroupBy = items
	}
	if p.acceptKw("having") {
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		sel.Having = e
	}
	return sel, nil
}

func (p *Parser) parseSelectItem() (ast.Item, error) {
	// * 与 t.*
	if p.atOp("*") {
		p.next()
		return ast.Item{Star: true}, nil
	}
	if p.cur().Kind == lexer.Ident && !reserved[strings.ToLower(p.cur().Text)] &&
		p.peek().Kind == lexer.Op && p.peek().Text == "." &&
		p.peekAt2().Kind == lexer.Op && p.peekAt2().Text == "*" {
		part := p.foldIdent(p.cur())
		p.next() // ident
		p.next() // .
		p.next() // *
		return ast.Item{Star: true, Prefix: []ast.IdentPart{part}}, nil
	}
	e, err := p.parseExpr()
	if err != nil {
		return ast.Item{}, err
	}
	item := ast.Item{Expr: e}
	if p.acceptKw("as") {
		a, err := p.expectIdentPart()
		if err != nil {
			return ast.Item{}, err
		}
		item.Alias = &a
	} else if p.aliasStart() {
		a := p.foldIdent(p.cur())
		p.next()
		item.Alias = &a
	}
	return item, nil
}

func (p *Parser) parseGroupItems() ([]ast.GroupItem, error) {
	var items []ast.GroupItem
	for {
		item, err := p.parseGroupItem()
		if err != nil {
			return nil, err
		}
		items = append(items, item)
		if !p.acceptOp(",") {
			return items, nil
		}
	}
}

func (p *Parser) parseGroupItem() (ast.GroupItem, error) {
	switch {
	case p.atKw("rollup"):
		p.next()
		exprs, err := p.parseParenExprList()
		if err != nil {
			return ast.GroupItem{}, err
		}
		return ast.GroupItem{Op: ast.GroupRollup, Exprs: exprs}, nil
	case p.atKw("cube"):
		p.next()
		exprs, err := p.parseParenExprList()
		if err != nil {
			return ast.GroupItem{}, err
		}
		return ast.GroupItem{Op: ast.GroupCube, Exprs: exprs}, nil
	case p.atKw("grouping"):
		p.next()
		if err := p.expectKw("sets"); err != nil {
			return ast.GroupItem{}, err
		}
		if err := p.expectOp("("); err != nil {
			return ast.GroupItem{}, err
		}
		var sets [][]ast.Expr
		for {
			if p.atOp("(") {
				exprs, err := p.parseParenExprList()
				if err != nil {
					return ast.GroupItem{}, err
				}
				sets = append(sets, exprs)
			} else {
				e, err := p.parseExpr()
				if err != nil {
					return ast.GroupItem{}, err
				}
				sets = append(sets, []ast.Expr{e})
			}
			if !p.acceptOp(",") {
				break
			}
		}
		if err := p.expectOp(")"); err != nil {
			return ast.GroupItem{}, err
		}
		return ast.GroupItem{Op: ast.GroupSets, Sets: sets}, nil
	default:
		e, err := p.parseExpr()
		if err != nil {
			return ast.GroupItem{}, err
		}
		return ast.GroupItem{Op: ast.GroupSimple, Expr: e}, nil
	}
}

// ---------------- FROM ----------------

// parseFromList 解析逗号分隔的表引用列表（每个元素是完整 JOIN 链）。
func (p *Parser) parseFromList() ([]ast.TableRef, error) {
	var refs []ast.TableRef
	for {
		ref, err := p.parseJoinChain()
		if err != nil {
			return nil, err
		}
		refs = append(refs, ref)
		if !p.acceptOp(",") {
			return refs, nil
		}
	}
}

func (p *Parser) parseJoinChain() (ast.TableRef, error) {
	left, err := p.parseTablePrimary()
	if err != nil {
		return nil, err
	}
	for {
		natural := false
		kind := ast.JoinInner
		switch {
		case p.atKw("natural"):
			natural = true
			p.next()
			switch {
			case p.acceptKw("join"):
				kind = ast.JoinInner
			case p.acceptKw("inner"):
				if err := p.expectKw("join"); err != nil {
					return nil, err
				}
			case p.atKw("left"), p.atKw("right"), p.atKw("full"):
				k, err := p.parseOuterKind()
				if err != nil {
					return nil, err
				}
				kind = k
			default:
				return nil, p.unexpected("JOIN")
			}
		case p.acceptKw("join"):
			kind = ast.JoinInner
		case p.acceptKw("inner"):
			if err := p.expectKw("join"); err != nil {
				return nil, err
			}
		case p.atKw("left"), p.atKw("right"), p.atKw("full"):
			k, err := p.parseOuterKind()
			if err != nil {
				return nil, err
			}
			kind = k
		case p.atKw("cross") && p.peekIsKw("join"):
			p.next()
			p.next()
			kind = ast.JoinCross
		default:
			return left, nil
		}
		right, err := p.parseTablePrimary()
		if err != nil {
			return nil, err
		}
		j := ast.Join{Pos: left.At(), Natural: natural, Kind: kind, Left: left, Right: right}
		if kind != ast.JoinCross && !natural {
			switch {
			case p.acceptKw("on"):
				e, err := p.parseExpr()
				if err != nil {
					return nil, err
				}
				j.On = e
			case p.acceptKw("using"):
				cols, err := p.parseIdentList()
				if err != nil {
					return nil, err
				}
				j.Using = cols
			default:
				return nil, p.unexpected("ON or USING")
			}
		}
		left = &j
	}
}

func (p *Parser) parseOuterKind() (ast.JoinKind, error) {
	switch {
	case p.acceptKw("left"):
		kind := ast.JoinLeft
		p.acceptKw("outer")
		if err := p.expectKw("join"); err != nil {
			return 0, err
		}
		return kind, nil
	case p.acceptKw("right"):
		p.acceptKw("outer")
		if err := p.expectKw("join"); err != nil {
			return 0, err
		}
		return ast.JoinRight, nil
	case p.acceptKw("full"):
		p.acceptKw("outer")
		if err := p.expectKw("join"); err != nil {
			return 0, err
		}
		return ast.JoinFull, nil
	default:
		return 0, p.unexpected("LEFT/RIGHT/FULL")
	}
}

func (p *Parser) parseTablePrimary() (ast.TableRef, error) {
	start := p.cur()
	lateral := false
	if p.acceptKw("lateral") {
		lateral = true
	}
	if p.atOp("(") {
		p.next()
		// 括号内容判别：查询（SELECT/WITH/VALUES/集合运算）还是 JOIN 链
		isQuery := p.parenContentIsQueryFrom(p.i)
		if isQuery {
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
			alias, err := p.parseTableAliasOpt()
			if err != nil {
				return nil, err
			}
			return &ast.Derived{Pos: posOf(start), Query: q, Alias: alias, Lateral: lateral}, nil
		}
		// 括号包住的 JOIN 链（或裸引用）
		ref, err := p.parseJoinChain()
		if err != nil {
			return nil, err
		}
		if err := p.expectOp(")"); err != nil {
			return nil, err
		}
		alias, err := p.parseTableAliasOpt()
		if err != nil {
			return nil, err
		}
		return &ast.FromParen{Pos: posOf(start), Ref: ref, Alias: alias}, nil
	}
	if p.atKw("unnest") {
		p.next()
		exprs, err := p.parseParenExprList()
		if err != nil {
			return nil, err
		}
		if len(exprs) != 1 {
			return nil, p.unexpected("a single UNNEST argument")
		}
		alias, err := p.parseTableAliasOpt()
		if err != nil {
			return nil, err
		}
		return &ast.Unnest{Pos: posOf(start), Expr: exprs[0], Alias: alias}, nil
	}
	// 表名（1–3 段）
	var parts []ast.IdentPart
	first, err := p.expectIdentPart()
	if err != nil {
		return nil, err
	}
	parts = append(parts, first)
	for p.atOp(".") && p.peekIsIdent() {
		p.next()
		part, err := p.expectIdentPart()
		if err != nil {
			return nil, err
		}
		parts = append(parts, part)
		if len(parts) > 3 {
			return nil, p.unexpected("a table reference with at most 3 name parts")
		}
	}
	alias, err := p.parseTableAliasOpt()
	if err != nil {
		return nil, err
	}
	return &ast.TableName{Pos: posOf(start), Parts: parts, Alias: alias}, nil
}

// parseTableAliasOpt 解析 [[AS] alias [(cols)]]。
func (p *Parser) parseTableAliasOpt() (*ast.TableAlias, error) {
	hasAs := p.acceptKw("as")
	if hasAs {
		name, err := p.expectIdentPart()
		if err != nil {
			return nil, err
		}
		alias := &ast.TableAlias{Name: name}
		if p.atOp("(") {
			cols, err := p.parseIdentList()
			if err != nil {
				return nil, err
			}
			alias.Columns = cols
		}
		return alias, nil
	}
	if p.aliasStart() {
		name := p.foldIdent(p.cur())
		p.next()
		alias := &ast.TableAlias{Name: name}
		if p.atOp("(") {
			cols, err := p.parseIdentList()
			if err != nil {
				return nil, err
			}
			alias.Columns = cols
		}
		return alias, nil
	}
	return nil, nil
}

func (p *Parser) parseIdentList() ([]ast.IdentPart, error) {
	if err := p.expectOp("("); err != nil {
		return nil, err
	}
	var parts []ast.IdentPart
	for {
		part, err := p.expectIdentPart()
		if err != nil {
			return nil, err
		}
		parts = append(parts, part)
		if !p.acceptOp(",") {
			break
		}
	}
	if err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return parts, nil
}

func (p *Parser) parseParenExprList() ([]ast.Expr, error) {
	if err := p.expectOp("("); err != nil {
		return nil, err
	}
	var exprs []ast.Expr
	if p.atOp(")") {
		p.next()
		return exprs, nil
	}
	for {
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		exprs = append(exprs, e)
		if !p.acceptOp(",") {
			break
		}
	}
	if err := p.expectOp(")"); err != nil {
		return nil, err
	}
	return exprs, nil
}
