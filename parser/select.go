package parser

import (
	"strings"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/lexer"
)

// 本文件实现 Task 7 的 SELECT 核心与 FROM 子句(查询层):
//
//	ParseQuery        = SELECT [DISTINCT|ALL] item {, item}
//	                    [ FROM 表引用链 [WHERE expr] [GROUP BY 项列表] [HAVING expr] ]
//	item              = * | t.* | expr [[AS] alias]
//	表引用链          = 表引用 { (, 表引用 | JOIN 系列 表引用) }   左结合单循环
//	表引用            = 表名(1–4 段)[[AS] alias [(cols)]]
//	                    | ( Query ) [[AS] alias [(cols)]]           派生表
//	JOIN 系列         = [NATURAL] [INNER|LEFT [OUTER]|RIGHT [OUTER]|
//	                     FULL [OUTER]|CROSS] JOIN 表引用 [ON expr|USING (..)]
//
// 结构对齐 fork(mask-sqlparser Parser.jj)的可观测文法:
//   - FromClause = 单个 TableRef1 + JoinOrCommaTable 左结合循环:逗号与 JOIN
//     同层、右操作数恒为单个表引用——`FROM a, b JOIN c ON e` 归约为
//     ((a, b) JOIN c ON e),`FROM a, b, c` 归约为嵌套 Comma Join(简报钉死);
//   - WHERE/GROUP BY/HAVING 只在 FROM 分支内(fork SqlSelect 产生式,jar 实测
//     `SELECT 1 WHERE ..`/`SELECT 1 GROUP BY ..`/`SELECT 1 HAVING ..` 均拒)。
//
// Task 8 在 ParseQuery 上扩展集合运算与 ORDER BY/LIMIT 等限尾(本文件不涉及,
// 这些关键字出现时成为残片、由入口的 EOF 检查拒绝);Task 9 扩展 WITH/VALUES
// 后,派生表 `( Query )` 经由同一 ParseQuery 入口自动获得相应能力。
//
// 决策 5 查询层落点:
//   - GROUP BY 语境的 ROLLUP:jar 实测 Java 解析接受(fork GroupingElementList
//     含 ROLLUP 产生式)且语料 tpcds_deep_cases.sql [D9] 实际使用——命中决策 5
//     「差分证明 Java 接受且语料需要,再按协议补」条款,按 ast.FunctionCall
//     形态接受(表达式语境的拒绝在 T6 expr.go,不受影响);
//   - GROUPING SETS 在 GROUP BY 位置报 PARSE_ERROR(决策 5;jar 实测 Java 解析
//     接受、语料零命中,差异记 T11 watchlist);
//   - CUBE(x) 在 GROUP BY 列表按普通函数表达式自然处理(简报明示);
//   - LATERAL 在 FROM 表引用位置报 PARSE_ERROR,与 T6 表达式层同文案
//     (决策 5「LATERAL 先不解析」;jar 实测 Java 解析接受、语料零命中,
//     差异记 T11 watchlist)。

// ParseQuery 解析裸 SELECT 查询体(不消费查询之后的 token,供子查询/派生表
// 复用;顶层入口的 EOF 检查由 ParseStatement(T8)承担)。Task 7 覆盖裸
// SELECT;后续任务在同一入口扩展其他 Query 形态。
func (p *Parser) ParseQuery() (ast.Query, error) {
	if p.lexErr != nil {
		return nil, p.lexErr
	}
	tok, err := p.expectKw("SELECT")
	if err != nil {
		return nil, err
	}
	sel := &ast.Select{Pos: tok.Pos}
	switch {
	case p.atKw("DISTINCT"):
		p.advance()
		sel.Distinct = true
	case p.atKw("ALL"):
		p.advance() // ALL 量词接受并忽略(与 T6 聚合限定词口径一致)
	}
	for {
		item, err := p.parseSelectItem()
		if err != nil {
			return nil, err
		}
		sel.Items = append(sel.Items, item)
		if p.atOp(",") {
			p.advance()
			continue
		}
		break
	}
	// WHERE/GROUP BY/HAVING 只在 FROM 分支内(镜像 fork SqlSelect 产生式;
	// jar 实测无 FROM 时这三个子句即残片报错)。
	if p.atKw("FROM") {
		p.advance()
		from, err := p.parseFrom()
		if err != nil {
			return nil, err
		}
		sel.From = from
		if p.atKw("WHERE") {
			p.advance()
			w, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			sel.Where = w
		}
		if p.atKw("GROUP") {
			p.advance()
			if _, err := p.expectKw("BY"); err != nil {
				return nil, err
			}
			gb, err := p.parseGroupByList()
			if err != nil {
				return nil, err
			}
			sel.GroupBy = gb
		}
		if p.atKw("HAVING") {
			p.advance()
			h, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			sel.Having = h
		}
	}
	return sel, nil
}

// ---------------------------------------------------------------------------
// 选择项
// ---------------------------------------------------------------------------

// clauseStartKw 不能起选择项的子句关键字(SELECT 后必须有项;这些词在 fork
// 文法中为保留字,出现在项位置即 ParseException——jar 实测 `SELECT FROM t`
// 报错于 FROM)。注意不含 CASE/NULL/JOIN 族等可起表达式的词。
var clauseStartKw = map[string]bool{
	"FROM": true, "WHERE": true, "GROUP": true, "HAVING": true, "WINDOW": true,
	"ORDER": true, "LIMIT": true, "OFFSET": true, "FETCH": true,
	"UNION": true, "INTERSECT": true, "EXCEPT": true,
	"VALUES": true, "WITH": true,
}

// aliasStopKw 不可作别名的未引号关键字(键为大写;QuotedIdent 永不命中)。
// 逐词经 jar 实测校准,分三类:
//   - 子句/查询结构词(clauseStartKw 全集 + SELECT/DISTINCT/AS);
//   - JOIN 族与连接条件词(LEFT/INNER/ON/USING 等——jar 实测均不可作别名,
//     且必须终止表引用以获得正确的 JOIN 树);
//   - 表达式结构词 jar 实测拒绝作别名者(WHEN/THEN/CASE/NULL)与运算结构词
//     (AND/OR/NOT/IN,只影响表别名位置——选择项位置这些词由 parseExpr
//     消费或在其残片处报错,到不了别名判定)。
//
// jar 实测可作别名(故不在集合):all/between/is/like/ilike/similar/end/else/
// exists/true/false 与决策 4 最小非保留集 top/overwrite/year/month/day/hour/
// minute/second。
var aliasStopKw = map[string]bool{
	"SELECT": true, "FROM": true, "WHERE": true, "GROUP": true, "HAVING": true,
	"WINDOW": true, "ORDER": true, "LIMIT": true, "OFFSET": true, "FETCH": true,
	"UNION": true, "INTERSECT": true, "EXCEPT": true,
	"VALUES": true, "WITH": true, "DISTINCT": true, "AS": true,
	"JOIN": true, "INNER": true, "LEFT": true, "RIGHT": true, "FULL": true,
	"OUTER": true, "CROSS": true, "NATURAL": true, "ON": true, "USING": true,
	"AND": true, "OR": true, "NOT": true, "IN": true,
	"WHEN": true, "THEN": true, "CASE": true, "NULL": true,
}

// aliasable 判定当前 token 可否作隐式别名(未引号且不在停用词集合)。
func (p *Parser) aliasable(tok lexer.Token) bool {
	if tok.Kind != lexer.Ident && tok.Kind != lexer.QuotedIdent {
		return false
	}
	if tok.Kind == lexer.Ident && aliasStopKw[strings.ToUpper(tok.Text)] {
		return false
	}
	return true
}

// simpleIdentFrom 由单个标识符 token 构造单段 Identifier(按 foldIdent 折算,
// Quoted 保真)。
func (p *Parser) simpleIdentFrom(tok lexer.Token) ast.Identifier {
	quoted := tok.Kind == lexer.QuotedIdent
	value := tok.Text
	if quoted {
		value = unquoteQuotedIdent(tok.Text)
	}
	return ast.Identifier{Pos: tok.Pos,
		Parts: []ast.IdentPart{{Value: p.foldIdent(value, quoted), Quoted: quoted}}}
}

// parseSelectItem 解析单个选择项:item = `*`(Star,StarQualifier 必空)|
// `t.*`/`s.t.*`(StarQualifier)| expr [[AS] alias](隐式别名按 Calcite 语义)。
//
// `t.*` 判定先以「试解析标识符链 + 回退游标」探测,非星形态回退后走完整
// parseExpr——保证 ROLLUP/LATERAL/X'..' 等表达式层拒绝(T6)与函数调用、
// 标量子查询等全部复用,不在本层重复实现。
func (p *Parser) parseSelectItem() (ast.SelectItem, error) {
	tok := p.curTok()
	if tok.Kind == lexer.Ident && clauseStartKw[strings.ToUpper(tok.Text)] {
		return ast.SelectItem{}, p.errAt(tok.Pos, "expected select item, found keyword %q", tok.Text)
	}
	if p.atOp("*") {
		p.advance()
		return ast.SelectItem{Star: true}, nil
	}
	if tok.Kind == lexer.Ident || tok.Kind == lexer.QuotedIdent {
		save := p.cur
		id, err := p.parseIdentifier()
		if err != nil {
			return ast.SelectItem{}, err
		}
		if p.atOp(".") && p.peekTok().Kind == lexer.Op && p.peekTok().Text == "*" {
			p.advance() // .
			p.advance() // *
			return ast.SelectItem{StarQualifier: id.Parts}, nil
		}
		p.cur = save // 非星形态:回退,按普通表达式重解析
	}
	e, err := p.parseExpr()
	if err != nil {
		return ast.SelectItem{}, err
	}
	item := ast.SelectItem{Expr: e}
	if p.atKw("AS") {
		p.advance()
		at := p.curTok()
		if !p.aliasable(at) {
			return ast.SelectItem{}, p.errAt(at.Pos, "expected alias after AS, found %s %q", at.Kind, at.Text)
		}
		p.advance()
		alias := p.simpleIdentFrom(at)
		item.Alias = &alias
	} else if p.aliasable(p.curTok()) {
		at := p.advance()
		alias := p.simpleIdentFrom(at)
		item.Alias = &alias
	}
	return item, nil
}

// ---------------------------------------------------------------------------
// FROM 链
// ---------------------------------------------------------------------------

// parseFrom 解析 FROM 表引用链:单个表引用后随左结合的逗号/JOIN 循环(镜像
// fork FromClause/JoinOrCommaTable)。返回单元素切片(整链归约为嵌套 Join 树;
// 空切片不变式不适用于本字段——FROM 必产恰一元素,无 FROM 时 From 为 nil)。
func (p *Parser) parseFrom() ([]ast.TableRef, error) {
	left, err := p.parseTableRefPrimary()
	if err != nil {
		return nil, err
	}
	for {
		switch {
		case p.atOp(","):
			comma := p.advance()
			right, err := p.parseTableRefPrimary()
			if err != nil {
				return nil, err
			}
			left = &ast.Join{Pos: comma.Pos, Kind: ast.Comma, Left: left, Right: right}
		case p.atKw("JOIN"), p.atKw("INNER"), p.atKw("LEFT"), p.atKw("RIGHT"),
			p.atKw("FULL"), p.atKw("CROSS"), p.atKw("NATURAL"):
			left, err = p.parseJoin(left)
			if err != nil {
				return nil, err
			}
		default:
			return []ast.TableRef{left}, nil
		}
	}
}

// parseJoin 解析一次连接:[NATURAL] 种别 JOIN 表引用 [连接条件]。Join.Pos 取
// 连接种别关键字(或逗号)位置,对齐 Java SqlJoin 的 joinType 位置口径。
// 连接条件按简报文法:INNER/LEFT/RIGHT/FULL 要求 ON expr 或 USING (c1, c2);
// CROSS/COMMA/NATURAL 不带条件(ast.Join 约定 Cross/Comma 两者皆空;jar 实测
// Java 对「无条件 INNER/LEFT/RIGHT/FULL」与「CROSS/NATURAL 带条件」均解析
// 接受、到校验期才失败——Go 按简报拒绝,差异记 T11 watchlist,语料零命中)。
func (p *Parser) parseJoin(left ast.TableRef) (ast.TableRef, error) {
	natural := false
	if p.atKw("NATURAL") {
		p.advance()
		natural = true
	}
	joinPos := p.curTok().Pos
	var kind ast.JoinKind
	switch {
	case p.atKw("JOIN"):
		p.advance()
		kind = ast.Inner
	case p.atKw("INNER"):
		p.advance()
		if _, err := p.expectKw("JOIN"); err != nil {
			return nil, err
		}
		kind = ast.Inner
	case p.atKw("LEFT"):
		p.advance()
		if p.atKw("OUTER") {
			p.advance()
		}
		if _, err := p.expectKw("JOIN"); err != nil {
			return nil, err
		}
		kind = ast.Left
	case p.atKw("RIGHT"):
		p.advance()
		if p.atKw("OUTER") {
			p.advance()
		}
		if _, err := p.expectKw("JOIN"); err != nil {
			return nil, err
		}
		kind = ast.Right
	case p.atKw("FULL"):
		p.advance()
		if p.atKw("OUTER") {
			p.advance()
		}
		if _, err := p.expectKw("JOIN"); err != nil {
			return nil, err
		}
		kind = ast.Full
	case p.atKw("CROSS"):
		p.advance()
		if _, err := p.expectKw("JOIN"); err != nil {
			return nil, err
		}
		kind = ast.Cross
	default:
		t := p.curTok()
		return nil, p.errAt(t.Pos, "expected JOIN, found %s %q", t.Kind, t.Text)
	}
	right, err := p.parseTableRefPrimary()
	if err != nil {
		return nil, err
	}
	j := &ast.Join{Pos: joinPos, Natural: natural, Kind: kind, Left: left, Right: right}
	switch kind {
	case ast.Cross, ast.Comma:
		return j, nil
	}
	if natural {
		return j, nil
	}
	if p.atKw("ON") {
		p.advance()
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		j.On = e
		return j, nil
	}
	if p.atKw("USING") {
		p.advance()
		if _, err := p.expectOp("("); err != nil {
			return nil, err
		}
		for {
			ct := p.curTok()
			if ct.Kind != lexer.Ident && ct.Kind != lexer.QuotedIdent {
				return nil, p.errAt(ct.Pos, "expected column name in USING, found %s %q", ct.Kind, ct.Text)
			}
			p.advance()
			j.Using = append(j.Using, p.simpleIdentFrom(ct))
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, err
		}
		return j, nil
	}
	t := p.curTok()
	return nil, p.errAt(t.Pos, "expected ON or USING after JOIN, found %s %q", t.Kind, t.Text)
}

// parseTableRefPrimary 解析单个表引用:表名(1–4 段)或派生表 `( Query )`,
// 两者均可带 [[AS] alias [(cols)]]。派生表经 ParseQuery 解析(子查询自动获得
// 完整查询能力;T8/T9 扩展集合运算/VALUES 后无需改动本处)。
func (p *Parser) parseTableRefPrimary() (ast.TableRef, error) {
	tok := p.curTok()
	switch {
	case p.atOp("("):
		paren := p.advance()
		q, err := p.ParseQuery()
		if err != nil {
			return nil, err
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, err
		}
		dt := &ast.DerivedTable{Pos: paren.Pos, Query: q}
		alias, err := p.parseTableAlias()
		if err != nil {
			return nil, err
		}
		dt.Alias = alias
		return dt, nil
	case tok.Kind == lexer.Ident && strings.EqualFold(tok.Text, "LATERAL"):
		// 决策 5:LATERAL 在 FROM 表引用位置拒绝(与 T6 表达式层同文案;
		// 引号标识符永不匹配关键字,不受影响)。
		return nil, p.errAt(tok.Pos, "Incorrect syntax near the keyword 'LATERAL'")
	case tok.Kind == lexer.Ident || tok.Kind == lexer.QuotedIdent:
		parts, err := p.parseTableIdentifier()
		if err != nil {
			return nil, err
		}
		ref := &ast.TableNameRef{Pos: tok.Pos, Parts: parts}
		alias, err := p.parseTableAlias()
		if err != nil {
			return nil, err
		}
		ref.Alias = alias
		return ref, nil
	default:
		return nil, p.errAt(tok.Pos, "expected table reference, found %s %q", tok.Kind, tok.Text)
	}
}

// parseTableIdentifier 解析表名多段标识符(1–4 段,简报 TableName 段数上限;
// 超 4 段报 PARSE_ERROR——jar 实测 Java 解析接受更长段名、语料零命中,差异记
// T11 watchlist)。每段为单段 Identifier(Pos 取该段 token,折算同 parseIdentifier)。
func (p *Parser) parseTableIdentifier() ([]ast.Identifier, error) {
	var parts []ast.Identifier
	for {
		tok := p.curTok()
		if tok.Kind != lexer.Ident && tok.Kind != lexer.QuotedIdent {
			if len(parts) == 0 {
				return nil, p.errAt(tok.Pos, "expected table name, found %s %q", tok.Kind, tok.Text)
			}
			return nil, p.errAt(tok.Pos, "expected table name after '.', found %s %q", tok.Kind, tok.Text)
		}
		p.advance()
		parts = append(parts, p.simpleIdentFrom(tok))
		if len(parts) > 4 {
			return nil, p.errAt(tok.Pos, "table name may have at most 4 parts, found %s %q", tok.Kind, tok.Text)
		}
		if !p.atOp(".") {
			return parts, nil
		}
		if nxt := p.peekTok(); nxt.Kind != lexer.Ident && nxt.Kind != lexer.QuotedIdent {
			// `a.*` 形态不属于表名(星号留给上层报错);悬点同样报错。
			return nil, p.errAt(nxt.Pos, "expected table name after '.', found %s %q", nxt.Kind, nxt.Text)
		}
		p.advance() // .
	}
}

// parseTableAlias 解析表引用的 [[AS] alias [(cols)]]:别名可缺省(jar 实测
// 派生表无别名亦解析接受),AS 与隐式两形态;列别名清单为括号内至少一个的
// 简单标识符。子句关键字终止隐式别名消费(不报错,交由外层分派);带 AS 时
// 遇停用词/非标识符则报错。
func (p *Parser) parseTableAlias() (*ast.TableAlias, error) {
	hasAs := false
	if p.atKw("AS") {
		p.advance()
		hasAs = true
	}
	tok := p.curTok()
	if !p.aliasable(tok) {
		if hasAs {
			return nil, p.errAt(tok.Pos, "expected table alias after AS, found %s %q", tok.Kind, tok.Text)
		}
		return nil, nil
	}
	p.advance()
	alias := &ast.TableAlias{Name: p.simpleIdentFrom(tok)}
	if p.atOp("(") {
		p.advance()
		for {
			ct := p.curTok()
			if ct.Kind != lexer.Ident && ct.Kind != lexer.QuotedIdent {
				return nil, p.errAt(ct.Pos, "expected column alias, found %s %q", ct.Kind, ct.Text)
			}
			p.advance()
			alias.Columns = append(alias.Columns, p.simpleIdentFrom(ct))
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, err
		}
	}
	return alias, nil
}

// ---------------------------------------------------------------------------
// GROUP BY 项列表
// ---------------------------------------------------------------------------

// parseGroupByList 解析 GROUP BY 项列表:逗号分隔的完整表达式,前置决策 5
// 语境检查——GROUPING SETS 报 PARSE_ERROR(未引号关键字;引号标识符不受
// 影响),ROLLUP(...) 按 jar 实测 + 语料证据接受为 ast.FunctionCall(见文件
// 头注释);CUBE(x) 等其余形态交由 parseExpr 按表达式规则自然处理。
func (p *Parser) parseGroupByList() ([]ast.Expr, error) {
	var list []ast.Expr
	for {
		switch {
		case p.atKw("GROUPING") && p.peekKw("SETS"):
			p.advance() // GROUPING(拒绝点落在 SETS 上,与 T6 表达式语境同族文案)
			sets := p.curTok()
			return nil, p.errAt(sets.Pos, "Incorrect syntax near the keyword 'SETS'")
		case p.atKw("ROLLUP"):
			tok := p.advance()
			if _, err := p.expectOp("("); err != nil {
				return nil, err
			}
			fc := &ast.FunctionCall{Pos: tok.Pos, Name: ast.Identifier{Pos: tok.Pos,
				Parts: []ast.IdentPart{{Value: p.foldIdent(tok.Text, false)}}}}
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
			if _, err := p.expectOp(")"); err != nil {
				return nil, err
			}
			list = append(list, fc)
		default:
			e, err := p.parseExpr()
			if err != nil {
				return nil, err
			}
			list = append(list, e)
		}
		if p.atOp(",") {
			p.advance()
			continue
		}
		return list, nil
	}
}
