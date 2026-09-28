package parser

import (
	"strings"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/lexer"
)

// 本文件实现查询层(Task 7 SELECT 核心与 FROM;Task 8 集合运算与限尾):
//
//	ParseQuery        = queryPrimary 集合运算链 [限尾]
//	queryPrimary      = SELECT [DISTINCT|ALL] [TOP (expr) | TOP n] item {, item}
//	                    [ FROM 表引用链 [WHERE expr] [GROUP BY 项列表] [HAVING expr] ]
//	                    | ( ParseQuery )                       括号查询(操作数/子查询)
//	集合运算链        = UNION [ALL|DISTINCT] / EXCEPT 同级左结合;
//	                    INTERSECT 优先级更高(镜像 Calcite toTree 优先级攀爬)
//	限尾              = [ORDER BY 项 {, 项}]
//	                    [ LIMIT n|ALL [OFFSET n [ROW|ROWS]]
//	                    | OFFSET n [ROW|ROWS] [FETCH FIRST|NEXT n [ROW|ROWS] ONLY]
//	                    | FETCH FIRST|NEXT n [ROW|ROWS] ONLY ]
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
//     `SELECT 1 WHERE ..`/`SELECT 1 GROUP BY ..`/`SELECT 1 HAVING ..` 均拒);
//   - 集合运算/限尾镜像 fork:集合运算链镜像 QueryOrExpr 的 AddSetOpQuery +
//     SqlParserUtil.toTree;限尾镜像 OrderedQueryOrExpr 的 OrderByLimitOpt
//     (SqlOrderBy 包装)——括号查询经由 ExprOrJoinOrOrderedQuery 的
//     LOOKAHEAD(2) Query+OrderByLimitOpt 分支,限尾同样留在括号内消费;
//   - 组合面按简报收窄:OFFSET 之后不再接 LIMIT(fork 该分支受
//     isOffsetLimitAllowed 门控,Go Profile 无对应开关,差异记 T11 watchlist),
//     LIMIT 与 FETCH 互斥(Calcite 同一 Fetch 产生式的两个分支);
//   - TOP 镜像 fork SqlSelect 的 SqlMaskTopN 挂点:SELECT [DISTINCT|ALL] 之后
//     TOP 后随 '(' 或无符号数字字面量才进入挂点,先解析值再做 conformance
//     检查——五方言 Profile.AllowTopN 均为 false,一律 PARSE_ERROR;门控读
//     Profile 字段而非硬编码;TOP 不在此形态时仍为决策 4 非保留标识符。
//
// Task 9 扩展 WITH/VALUES 后,派生表 `( Query )` 经由同一 ParseQuery 入口
// 自动获得相应能力。
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

// ParseQuery 解析完整查询体:查询基元 + 集合运算链 + statement 级限尾
// (ORDER BY/LIMIT/OFFSET/FETCH → ast.OrderBy 包装,镜像 fork OrderByLimitOpt
// 对 OrderedQueryOrExpr 的挂法;括号内的限尾由括号内的同一入口消费,不越出
// 括号)。不消费查询之后的 token(供子查询/派生表复用),顶层入口的 EOF
// 检查由 ParseStatement 承担。Task 7 覆盖裸 SELECT;Task 8 扩展集合运算与
// 限尾;Task 9 扩展 WITH 头与 VALUES 基元(stmt.go)后,子查询/派生表/
// INSERT 源/CTAS 源经同一入口自动获得相应能力。
//
// WITH 挂在头位(镜像 QueryOrExpr 的可选 WithList,包住集合运算归约后的
// 查询体,parseWith 内部再挂限尾)——集合运算操作数位不消费 WITH,与 fork
// LeafQuery 的接受面一致;VALUES/VALUE 为查询基元(镜像 LeafQuery 的
// TableConstructor 备选)。
func (p *Parser) ParseQuery() (ast.Query, error) {
	if p.lexErr != nil {
		return nil, p.lexErr
	}
	if p.atKw("WITH") {
		return p.parseWith()
	}
	head, topFetch, err := p.parseQueryPrimary()
	if err != nil {
		return nil, err
	}
	q, err := p.parseSetOpExpr(head)
	if err != nil {
		return nil, err
	}
	return p.parseOrderAndTail(q, topFetch, q != head)
}

// parseQueryPrimary 查询基元:括号查询、VALUES 行集(Task 9,stmt.go)或
// 裸 SELECT 头。括号内交还 ParseQuery(集合运算与限尾留在括号内消费,镜像
// fork ExprOrJoinOrOrderedQuery 的 LOOKAHEAD(2) Query+OrderByLimitOpt 分支;
// 括号不产生额外包装节点)。第二返回值为裸 SELECT 头的 TOP fetch(仅
// Profile.AllowTopN 开启时非 nil,五方言均关、M1 不可达;见 parseTopN)。
func (p *Parser) parseQueryPrimary() (ast.Query, ast.Expr, error) {
	if p.atOp("(") {
		p.advance()
		q, err := p.ParseQuery()
		if err != nil {
			return nil, nil, err
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, nil, err
		}
		return q, nil, nil
	}
	if p.atKw("VALUES") || p.atKw("VALUE") {
		v, err := p.parseValues()
		return v, nil, err
	}
	return p.parseSelectHead()
}

// parseSelectHead 解析裸 SELECT 查询体(T7 文法)+ TOP 挂点:
//
//	SELECT [DISTINCT|ALL] [TOP (expr) | TOP n] item {, item}
//	       [ FROM 表引用链 [WHERE expr] [GROUP BY 项列表] [HAVING expr] ]
func (p *Parser) parseSelectHead() (ast.Query, ast.Expr, error) {
	tok, err := p.expectKw("SELECT")
	if err != nil {
		return nil, nil, err
	}
	sel := &ast.Select{Pos: tok.Pos}
	switch {
	case p.atKw("DISTINCT"):
		p.advance()
		sel.Distinct = true
	case p.atKw("ALL"):
		p.advance() // ALL 量词接受并忽略(与 T6 聚合限定词口径一致)
	}
	// TOP 挂点(fork SqlMaskTopN):LOOKAHEAD 限定 TOP 后随 '(' 或无符号数字
	// 字面量才进入;其余形态 TOP 仍是决策 4 非保留标识符,走普通选择项。
	var topFetch ast.Expr
	if p.atKw("TOP") {
		nxt := p.peekTok()
		if (nxt.Kind == lexer.Op && nxt.Text == "(") || nxt.Kind == lexer.Number {
			if topFetch, err = p.parseTopN(); err != nil {
				return nil, nil, err
			}
		}
	}
	for {
		item, err := p.parseSelectItem()
		if err != nil {
			return nil, nil, err
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
			return nil, nil, err
		}
		sel.From = from
		if p.atKw("WHERE") {
			p.advance()
			w, err := p.parseExpr()
			if err != nil {
				return nil, nil, err
			}
			sel.Where = w
		}
		if p.atKw("GROUP") {
			p.advance()
			if _, err := p.expectKw("BY"); err != nil {
				return nil, nil, err
			}
			gb, err := p.parseGroupByList()
			if err != nil {
				return nil, nil, err
			}
			sel.GroupBy = gb
		}
		if p.atKw("HAVING") {
			p.advance()
			h, err := p.parseExpr()
			if err != nil {
				return nil, nil, err
			}
			sel.Having = h
		}
	}
	return sel, topFetch, nil
}

// ---------------------------------------------------------------------------
// TOP 挂点(Task 8)
// ---------------------------------------------------------------------------

// parseTopN 解析 SELECT [DISTINCT|ALL] 之后的 TOP:TOP ( expr ) 或
// TOP 无符号数字字面量(fork SqlMaskTopN,Expression(ACCEPT_SUB_QUERY) 支持括号
// 内任意表达式)。顺序镜像 fork:先解析值,再做 conformance 检查——五方言
// Profile.AllowTopN 均为 false,一律在此报 PARSE_ERROR(message 含 TOP;
// 门控读 Profile 字段而非硬编码,未来方言开启时走 PERCENT/WITH TIES 检查,
// TOP 值由调用方并入 OrderBy.Fetch 承载)。
func (p *Parser) parseTopN() (ast.Expr, error) {
	top := p.advance() // TOP
	var val ast.Expr
	if p.atOp("(") {
		p.advance()
		e, err := p.parseExpr()
		if err != nil {
			return nil, err
		}
		if _, err := p.expectOp(")"); err != nil {
			return nil, err
		}
		val = e
	} else {
		t := p.curTok()
		if t.Kind != lexer.Number {
			return nil, p.errAt(t.Pos, "expected unsigned numeric literal or '(' after TOP, found %s %q", t.Kind, t.Text)
		}
		p.advance()
		val = &ast.Literal{Pos: t.Pos, Kind: numberLiteralKind(t.Text), Text: t.Text}
	}
	if !p.profile.AllowTopN {
		return nil, p.errAt(top.Pos, "TOP is not enabled for this dialect")
	}
	if p.atKw("PERCENT") {
		t := p.advance()
		return nil, p.errAt(t.Pos, "TOP ... PERCENT is not supported")
	}
	if p.atKw("WITH") && p.peekKw("TIES") {
		t := p.advance()
		p.advance()
		return nil, p.errAt(t.Pos, "TOP ... WITH TIES is not supported")
	}
	return val, nil
}

// attachTopFetch TOP 值在集合运算操作数位置的落点(AllowTopN 开启时才可达;
// fork 把 fetch 挂在操作数自己的 SqlSelect.fetch,Go AST 的 fetch 唯一落点是
// OrderBy 包装,故以包装等价承载)。不在此消费限尾,交由外层。
func attachTopFetch(q ast.Query, top ast.Expr) ast.Query {
	if top == nil {
		return q
	}
	return &ast.OrderBy{Pos: q.Position(), Query: q, Fetch: top}
}

// ---------------------------------------------------------------------------
// 集合运算链(Task 8)
// ---------------------------------------------------------------------------

// parseSetOpExpr 集合运算链,镜像 fork AddSetOpQuery + SqlParserUtil.toTree 的
// 优先级攀爬:先归约左起 INTERSECT 链(优先级高),再进入 UNION/EXCEPT 同级
// 左结合循环。left 为已解析的左操作数。
func (p *Parser) parseSetOpExpr(left ast.Query) (ast.Query, error) {
	for p.atKw("INTERSECT") {
		t := p.advance()
		all := p.setOpQuantifier()
		right, top, err := p.parseQueryPrimary()
		if err != nil {
			return nil, err
		}
		left = &ast.SetOp{Pos: t.Pos, Op: ast.Intersect, All: all,
			Left: left, Right: attachTopFetch(right, top)}
	}
	for {
		var op ast.SetOpKind
		switch {
		case p.atKw("UNION"):
			op = ast.Union
		case p.atKw("EXCEPT"):
			op = ast.Except
		default:
			return left, nil
		}
		t := p.advance()
		all := p.setOpQuantifier()
		right, err := p.parseSetOpTerm()
		if err != nil {
			return nil, err
		}
		left = &ast.SetOp{Pos: t.Pos, Op: op, All: all, Left: left, Right: right}
	}
}

// parseSetOpTerm UNION/EXCEPT 的右操作数层级:查询基元 + 紧随的 INTERSECT
// 左结合链(INTERSECT 优先级高于 UNION/EXCEPT,先归约)。
func (p *Parser) parseSetOpTerm() (ast.Query, error) {
	left, top, err := p.parseQueryPrimary()
	if err != nil {
		return nil, err
	}
	left = attachTopFetch(left, top)
	for p.atKw("INTERSECT") {
		t := p.advance()
		all := p.setOpQuantifier()
		right, rtop, err := p.parseQueryPrimary()
		if err != nil {
			return nil, err
		}
		left = &ast.SetOp{Pos: t.Pos, Op: ast.Intersect, All: all,
			Left: left, Right: attachTopFetch(right, rtop)}
	}
	return left, nil
}

// setOpQuantifier 消费集合运算量词:ALL → true;DISTINCT → false;缺省 →
// false(显式 DISTINCT 与缺省同义)。
func (p *Parser) setOpQuantifier() bool {
	if p.atKw("ALL") {
		p.advance()
		return true
	}
	if p.atKw("DISTINCT") {
		p.advance()
	}
	return false
}

// ---------------------------------------------------------------------------
// statement 级限尾(Task 8)
// ---------------------------------------------------------------------------

// parseOrderAndTail 解析查询之后的 ORDER BY/LIMIT/OFFSET/FETCH 并包装为
// ast.OrderBy(镜像 fork OrderByLimitOpt 产出 SqlOrderBy):
//
//	[ORDER BY 项 {, 项}]
//	[ LIMIT n|ALL [OFFSET n [ROW|ROWS]]
//	| OFFSET n [ROW|ROWS] [FETCH FIRST|NEXT n [ROW|ROWS] ONLY]
//	| FETCH FIRST|NEXT n [ROW|ROWS] ONLY ]
//
// 组合面按简报:LIMIT 后可跟 OFFSET;OFFSET 后可跟 FETCH;LIMIT 与 FETCH 互斥
// (Calcite 同一 Fetch 产生式的两个分支);OFFSET 之后不接 LIMIT(fork 该分支
// 受 isOffsetLimitAllowed 门控,Go Profile 无对应开关,差异记 T11 watchlist)。
// topFetch 非 nil(TOP 挂点,AllowTopN 开启时才可达)时并入包装的 Fetch,与
// LIMIT/OFFSET/FETCH 同现即冲突(fork OrderByLimitOpt 的 TOP 冲突检查);
// setOpSeen 表示左操作数已经过集合运算归约——TOP 隶属单个 SELECT 头,与集合
// 运算组合在 Go 侧不落位,保守拒绝(差异记 T11 watchlist,fork 接受)。
// 无任何限尾成分时原样返回 q。
func (p *Parser) parseOrderAndTail(q ast.Query, topFetch ast.Expr, setOpSeen bool) (ast.Query, error) {
	var (
		items    []ast.OrderItem
		orderPos lexer.Pos
		hasOrder bool
	)
	if p.atKw("ORDER") {
		t := p.advance()
		orderPos, hasOrder = t.Pos, true
		if _, err := p.expectKw("BY"); err != nil {
			return nil, err
		}
		for {
			item, err := p.parseOrderItem()
			if err != nil {
				return nil, err
			}
			items = append(items, item)
			if p.atOp(",") {
				p.advance()
				continue
			}
			break
		}
	}
	var (
		limit, offset, fetch ast.Expr
		tailPos              lexer.Pos
		hasTail              bool
	)
	switch {
	case p.atKw("LIMIT"):
		t := p.advance()
		tailPos, hasTail = t.Pos, true
		if p.atKw("ALL") {
			p.advance()
			// 镜像 Calcite:LIMIT ALL 归约为精确数值 -1(SqlOrderBy.fetch = -1)。
			limit = &ast.Literal{Pos: t.Pos, Kind: ast.Int, Text: "-1"}
		} else {
			var err error
			if limit, err = p.parseTailValue(); err != nil {
				return nil, err
			}
		}
		// LIMIT start, count(T11 jar 实测:conformance 门控——pg/trino 拒绝
		// 'LIMIT start, count' is not allowed...,MySQL5/Lenient 接受;逗号
		// 形态后可随 OFFSET 实测接受)。AST 归一为 Limit=count,Offset=start。
		if p.atOp(",") {
			comma := p.advance()
			if p.profile.Conformance == dialect.Default {
				return nil, p.errAt(comma.Pos, "'LIMIT start, count' is not allowed under the current SQL conformance level")
			}
			start := limit
			var count ast.Expr
			var err error
			if count, err = p.parseTailValue(); err != nil {
				return nil, err
			}
			limit, offset = count, start
		}
		if p.atKw("OFFSET") {
			p.advance()
			var err error
			if offset, err = p.parseTailValue(); err != nil {
				return nil, err
			}
			p.skipRowOrRows()
		}
		if p.atKw("FETCH") {
			ft := p.curTok()
			return nil, p.errAt(ft.Pos, "FETCH cannot be combined with LIMIT")
		}
	case p.atKw("OFFSET"):
		t := p.advance()
		tailPos, hasTail = t.Pos, true
		var err error
		if offset, err = p.parseTailValue(); err != nil {
			return nil, err
		}
		p.skipRowOrRows()
		// OFFSET n [ROW|ROWS] LIMIT m(T11 jar 实测:仅 Lenient 档接受,
		// pg/MySQL5 拒绝 'OFFSET start LIMIT count' is not allowed...;Lenient
		// 下 ROWS 计量词亦接受)。AST 归一为 Limit=m,Offset=n。
		if p.atKw("LIMIT") {
			lt := p.advance()
			if p.profile.Conformance != dialect.Lenient {
				return nil, p.errAt(lt.Pos, "'OFFSET start LIMIT count' is not allowed under the current SQL conformance level")
			}
			var count ast.Expr
			if count, err = p.parseTailValue(); err != nil {
				return nil, err
			}
			limit = count
		}
		if p.atKw("FETCH") {
			p.advance()
			if fetch, err = p.parseFetchOnly(); err != nil {
				return nil, err
			}
		}
	case p.atKw("FETCH"):
		t := p.advance()
		tailPos, hasTail = t.Pos, true
		var err error
		if fetch, err = p.parseFetchOnly(); err != nil {
			return nil, err
		}
	}
	if topFetch != nil && (limit != nil || offset != nil || fetch != nil) {
		// fork OrderByLimitOpt:TOP(即 fetch 已置)与 OFFSET/LIMIT/FETCH 冲突。
		return nil, p.errAt(tailPos, "TOP cannot be combined with OFFSET/LIMIT/FETCH")
	}
	if topFetch != nil && setOpSeen {
		return nil, p.errAt(q.Position(), "TOP cannot be combined with set operators")
	}
	if !hasOrder && !hasTail && topFetch == nil {
		return q, nil
	}
	pos := orderPos
	if !hasOrder {
		pos = tailPos
	}
	if !hasOrder && !hasTail {
		pos = q.Position()
	}
	if fetch == nil {
		fetch = topFetch
	}
	return &ast.OrderBy{Pos: pos, Query: q, Items: items, Limit: limit, Offset: offset, Fetch: fetch}, nil
}

// parseTailValue 解析 LIMIT/OFFSET/FETCH 的量值:无符号数字字面量或动态参数
// (镜像 fork UnsignedNumericLiteralOrParam)。
func (p *Parser) parseTailValue() (ast.Expr, error) {
	t := p.curTok()
	switch t.Kind {
	case lexer.Number:
		p.advance()
		return &ast.Literal{Pos: t.Pos, Kind: numberLiteralKind(t.Text), Text: t.Text}, nil
	case lexer.Param:
		p.advance()
		return paramNode(t), nil
	default:
		return nil, p.errAt(t.Pos, "expected unsigned numeric literal or parameter, found %s %q", t.Kind, t.Text)
	}
}

// skipRowOrRows 消费可选的 ROW|ROWS 计量词(fork OffsetClause 中可选,
// Postgres 风格 OFFSET 不带计量词)。
func (p *Parser) skipRowOrRows() {
	if p.atKw("ROW") || p.atKw("ROWS") {
		p.advance()
	}
}

// parseFetchOnly 解析 FETCH 之后的尾部:FIRST|NEXT n [ROW|ROWS] ONLY
// (fork FetchClause:计量词 ROW|ROWS 与 ONLY 均必需)。
func (p *Parser) parseFetchOnly() (ast.Expr, error) {
	if !p.atKw("FIRST") && !p.atKw("NEXT") {
		t := p.curTok()
		return nil, p.errAt(t.Pos, "expected FIRST or NEXT after FETCH, found %s %q", t.Kind, t.Text)
	}
	p.advance()
	v, err := p.parseTailValue()
	if err != nil {
		return nil, err
	}
	if p.atKw("ROW") || p.atKw("ROWS") {
		p.advance()
	} else {
		t := p.curTok()
		return nil, p.errAt(t.Pos, "expected ROW or ROWS before ONLY, found %s %q", t.Kind, t.Text)
	}
	if _, err := p.expectKw("ONLY"); err != nil {
		return nil, err
	}
	return v, nil
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
//
// fix round 1(Task 9)补测增补:UPDATE/DELETE/MERGE/TABLE/SET/DESCRIBE/CALL
// 七词经 jar 实测(Probe5/Probe6)为 fork 保留 token——别名(SELECT 1 update)、
// CTE 名(WITH update AS ..)、INSERT 列名((update))全部 FAIL,而 BEGIN/
// COMMIT/ROLLBACK/SHOW/DISCARD 经同法实测可作别名(LOOKAHEAD 触发的 postgres
// 语句,词本身非保留)故不入集。该集合同时服务语句首词护栏(firstWordTailShape)。
var aliasStopKw = map[string]bool{
	"SELECT": true, "FROM": true, "WHERE": true, "GROUP": true, "HAVING": true,
	"WINDOW": true, "ORDER": true, "LIMIT": true, "OFFSET": true, "FETCH": true,
	"UNION": true, "INTERSECT": true, "EXCEPT": true,
	"VALUES": true, "WITH": true, "DISTINCT": true, "AS": true,
	"JOIN": true, "INNER": true, "LEFT": true, "RIGHT": true, "FULL": true,
	"OUTER": true, "CROSS": true, "NATURAL": true, "ON": true, "USING": true,
	"AND": true, "OR": true, "NOT": true, "IN": true,
	"WHEN": true, "THEN": true, "CASE": true, "NULL": true,
	"UPDATE": true, "DELETE": true, "MERGE": true, "TABLE": true,
	"SET": true, "DESCRIBE": true, "CALL": true,
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
			// 编码约定(ast.SelectItem 文档):星项 Star 恒 true,限定段落
			// StarQualifier;表达式项 Star=false 且 StarQualifier 必空。
			return ast.SelectItem{Star: true, StarQualifier: id.Parts}, nil
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

// parseTableIdentifier 解析表名多段标识符(段数无上限;T11 jar 实测 5 段
// a.b.c.d.e 解析接受——复合标识符在解析期不限段数,语义校验属 M2)。每段为
// 单段 Identifier(Pos 取该段 token,折算同 parseIdentifier)。
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
