package rowfilter

import (
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/config"
	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
)

// Result 是注入结果：改写后的语句 + 注入次数。
type Result struct {
	Node       ast.Statement
	Injections int
}

// Rewriter 在校验/血缘之前把受控表的 FROM 引用替换为
// (SELECT * FROM t WHERE cond) AS alias。不修改输入树；CTE 名遮蔽基表；
// register-after-rewrite；同名表多处引用独立注入。
type Rewriter struct {
	registry *Registry
	cfg      *config.LoadedConfig
}

// NewRewriter 构造（PG：大小写敏感名字匹配、不解析 2 段名）。
func NewRewriter(cfg *config.LoadedConfig, registry *Registry) *Rewriter {
	return &Rewriter{registry: registry, cfg: cfg}
}

// Apply 注入；registry 为空时原样返回。
func (rw *Rewriter) Apply(stmt ast.Statement) (Result, error) {
	if rw.registry.IsEmpty() {
		return Result{Node: stmt}, nil
	}
	ctx := &ctx{rw: rw}
	rewritten, err := rw.rewriteQuery(stmt, ctx, nil)
	if err != nil {
		return Result{}, err
	}
	if ctx.injections > 0 {
		if err := rejectQualifiedColumnReferences(rw, stmt, ctx); err != nil {
			return Result{}, err
		}
	}
	return Result{Node: rewritten, Injections: ctx.injections}, nil
}

type ctx struct {
	rw         *Rewriter
	injections int
}

// cteScopes 是作用域栈：外层 map 的 key 是 CTE 名；每层的 []string 是该层
// 已完成声明的名字（register-after-rewrite：body 改写完才入列）。
type cteScopes = []map[string]bool

func (rw *Rewriter) rewriteQuery(node ast.Statement, c *ctx, scopes cteScopes) (ast.Statement, error) {
	switch n := node.(type) {
	case *ast.Select:
		return rw.rewriteSelect(n, c, scopes)
	case *ast.With:
		return rw.rewriteWith(n, c, scopes)
	case *ast.OrderBy:
		q, err := rw.rewriteQuery(ast.AsStatement(n.Query), c, scopes)
		if err != nil {
			return nil, err
		}
		items, err := rw.rewriteOrderItems(n.Items, c, scopes)
		if err != nil {
			return nil, err
		}
		offset, err := rw.rewriteExprOpt(n.Offset, c, scopes)
		if err != nil {
			return nil, err
		}
		fetch, err := rw.rewriteExprOpt(n.Fetch, c, scopes)
		if err != nil {
			return nil, err
		}
		limit, err := rw.rewriteExprOpt(n.Limit, c, scopes)
		if err != nil {
			return nil, err
		}
		if sameQuery(ast.AsQuery(q), n.Query) && sameItems(items, n.Items) && offset == n.Offset &&
			fetch == n.Fetch && limit == n.Limit {
			return n, nil
		}
		return &ast.OrderBy{Pos: n.Pos, Query: ast.AsQuery(q), Items: items,
			Limit: limit, LimitAll: n.LimitAll, Offset: offset, Fetch: fetch}, nil
	case *ast.SetOp:
		left, err := rw.rewriteQuery(ast.AsStatement(n.Left), c, scopes)
		if err != nil {
			return nil, err
		}
		right, err := rw.rewriteQuery(ast.AsStatement(n.Right), c, scopes)
		if err != nil {
			return nil, err
		}
		if sameQuery(ast.AsQuery(left), n.Left) && sameQuery(ast.AsQuery(right), n.Right) {
			return n, nil
		}
		return &ast.SetOp{Pos: n.Pos, Op: n.Op, All: n.All, Left: ast.AsQuery(left), Right: ast.AsQuery(right)}, nil
	case *ast.Values:
		rows := make([][]ast.Expr, len(n.Rows))
		changed := false
		for i, row := range n.Rows {
			nr, err := rw.rewriteExprList(row, c, scopes)
			if err != nil {
				return nil, err
			}
			rows[i] = nr
			changed = changed || !sameExprList(nr, row)
		}
		if !changed {
			return n, nil
		}
		return &ast.Values{Pos: n.Pos, Rows: rows}, nil
	default:
		return node, nil
	}
}

func (rw *Rewriter) rewriteWith(n *ast.With, c *ctx, scopes cteScopes) (ast.Statement, error) {
	scope := map[string]bool{}
	scopes = append(scopes, scope)
	items := make([]ast.WithItem, len(n.Items))
	changed := false
	for i, item := range n.Items {
		body, err := rw.rewriteQuery(ast.AsStatement(item.Body), c, scopes)
		if err != nil {
			return nil, err
		}
		// register-after-rewrite：自己的 body 改写完才入 scope
		scope[strings.ToLower(item.Name.Value)] = true
		if !sameQuery(ast.AsQuery(body), item.Body) {
			items[i] = ast.WithItem{Pos: item.Pos, Name: item.Name, Columns: item.Columns, Body: ast.AsQuery(body)}
			changed = true
		} else {
			items[i] = item
		}
	}
	body, err := rw.rewriteQuery(ast.AsStatement(n.Body), c, scopes)
	if err != nil {
		return nil, err
	}
	if !changed && sameQuery(ast.AsQuery(body), n.Body) {
		return n, nil
	}
	return &ast.With{Pos: n.Pos, Recursive: n.Recursive, Items: items, Body: ast.AsQuery(body)}, nil
}

func (rw *Rewriter) rewriteSelect(n *ast.Select, c *ctx, scopes cteScopes) (ast.Statement, error) {
	out := &ast.Select{Pos: n.Pos, Distinct: n.Distinct}
	changed := false
	// FROM
	for _, ref := range n.From {
		nr, err := rw.rewriteFromItem(ref, c, scopes)
		if err != nil {
			return nil, err
		}
		if nr != ref {
			changed = true
		}
		out.From = append(out.From, nr)
	}
	// items
	for _, it := range n.Items {
		ni, ne, err := rw.rewriteItem(it, c, scopes)
		if err != nil {
			return nil, err
		}
		if ne {
			changed = true
		}
		out.Items = append(out.Items, ni)
	}
	if n.Where != nil {
		w, err := rw.rewriteExpr(n.Where, c, scopes)
		if err != nil {
			return nil, err
		}
		if w != n.Where {
			changed = true
		}
		out.Where = w
	}
	for _, g := range n.GroupBy {
		ng, ge, err := rw.rewriteGroupItem(g, c, scopes)
		if err != nil {
			return nil, err
		}
		if ge {
			changed = true
		}
		out.GroupBy = append(out.GroupBy, ng)
	}
	if n.Having != nil {
		h, err := rw.rewriteExpr(n.Having, c, scopes)
		if err != nil {
			return nil, err
		}
		if h != n.Having {
			changed = true
		}
		out.Having = h
	}
	if !changed {
		return n, nil
	}
	return out, nil
}

func (rw *Rewriter) rewriteItem(it ast.Item, c *ctx, scopes cteScopes) (ast.Item, bool, error) {
	if it.Star {
		return it, false, nil
	}
	e, err := rw.rewriteExpr(it.Expr, c, scopes)
	if err != nil {
		return ast.Item{}, false, err
	}
	if e == it.Expr {
		return it, false, nil
	}
	return ast.Item{Expr: e, Alias: it.Alias}, true, nil
}

func (rw *Rewriter) rewriteGroupItem(g ast.GroupItem, c *ctx, scopes cteScopes) (ast.GroupItem, bool, error) {
	switch g.Op {
	case ast.GroupSimple:
		e, err := rw.rewriteExpr(g.Expr, c, scopes)
		if err != nil {
			return ast.GroupItem{}, false, err
		}
		if e == g.Expr {
			return g, false, nil
		}
		return ast.GroupItem{Op: g.Op, Expr: e}, true, nil
	case ast.GroupRollup, ast.GroupCube:
		exprs, err := rw.rewriteExprList(g.Exprs, c, scopes)
		if err != nil {
			return ast.GroupItem{}, false, err
		}
		if sameExprList(exprs, g.Exprs) {
			return g, false, nil
		}
		return ast.GroupItem{Op: g.Op, Exprs: exprs}, true, nil
	default:
		sets := make([][]ast.Expr, len(g.Sets))
		changed := false
		for i, s := range g.Sets {
			ns, err := rw.rewriteExprList(s, c, scopes)
			if err != nil {
				return ast.GroupItem{}, false, err
			}
			sets[i] = ns
			changed = changed || !sameExprList(ns, s)
		}
		if !changed {
			return g, false, nil
		}
		return ast.GroupItem{Op: g.Op, Sets: sets}, true, nil
	}
}

func (rw *Rewriter) rewriteOrderItems(items []ast.OrderItem, c *ctx, scopes cteScopes) ([]ast.OrderItem, error) {
	out := make([]ast.OrderItem, len(items))
	for i, it := range items {
		e, err := rw.rewriteExpr(it.Expr, c, scopes)
		if err != nil {
			return nil, err
		}
		out[i] = it
		if e != it.Expr {
			out[i].Expr = e
		}
	}
	return out, nil
}

// rewriteFromItem FROM 项：表引用 / 别名 / JOIN / 派生表 / 未知形态。
func (rw *Rewriter) rewriteFromItem(ref ast.TableRef, c *ctx, scopes cteScopes) (ast.TableRef, error) {
	switch n := ref.(type) {
	case *ast.TableName:
		return rw.resolveTableReference(n, c, scopes)
	case *ast.FromParen:
		inner, err := rw.rewriteFromItem(n.Ref, c, scopes)
		if err != nil {
			return nil, err
		}
		if inner == n.Ref {
			return n, nil
		}
		return &ast.FromParen{Pos: n.Pos, Ref: inner, Alias: n.Alias}, nil
	case *ast.Derived:
		q, err := rw.rewriteQuery(ast.AsStatement(n.Query), c, scopes)
		if err != nil {
			return nil, err
		}
		if sameQuery(ast.AsQuery(q), n.Query) {
			return n, nil
		}
		return &ast.Derived{Pos: n.Pos, Query: ast.AsQuery(q), Alias: n.Alias, Lateral: n.Lateral}, nil
	case *ast.Join:
		left, err := rw.rewriteFromItem(n.Left, c, scopes)
		if err != nil {
			return nil, err
		}
		right, err := rw.rewriteFromItem(n.Right, c, scopes)
		if err != nil {
			return nil, err
		}
		var on ast.Expr
		if n.On != nil {
			on, err = rw.rewriteExpr(n.On, c, scopes)
			if err != nil {
				return nil, err
			}
		}
		if left == n.Left && right == n.Right && on == n.On {
			return n, nil
		}
		return &ast.Join{Pos: n.Pos, Natural: n.Natural, Kind: n.Kind, Left: left, Right: right, On: on, Using: n.Using}, nil
	case *ast.Unnest:
		return rw.failClosedOnUnknownFrom(ref, c)
	default:
		return rw.failClosedOnUnknownFrom(ref, c)
	}
}

// failClosedOnUnknownFrom 白名单外 FROM 形态：子树提及受控表即拒绝。
func (rw *Rewriter) failClosedOnUnknownFrom(from ast.TableRef, c *ctx) (ast.TableRef, error) {
	for _, t := range c.rw.cfg.Tables {
		if !c.rw.registry.IsControlled(t.Catalog, t.Schema, t.Name) {
			continue
		}
		if subtreeMentionsTable(from, t) {
			return nil, maskerr.Errorf(maskerr.UnsupportedStatement,
				"unsupported FROM clause shape %s involving filtered table '%s'; the row filter cannot be injected safely",
				fromKindName(from), t.QualifiedName())
		}
	}
	return from, nil
}

func fromKindName(ref ast.TableRef) string {
	switch ref.(type) {
	case *ast.Unnest:
		return "UNNEST"
	default:
		return "OTHER"
	}
}

// subtreeMentionsTable 对齐 RowFilterRewriter.subtreeMentionsTable：
// 1 段名=表名 或 3 段全限定即视为提及。
func subtreeMentionsTable(node ast.Node, t *metadata.Table) bool {
	if node == nil {
		return false
	}
	switch n := node.(type) {
	case *ast.Ident:
		if len(n.Parts) == 1 && nameMatches(n.Parts[0].Value, t.Name) {
			return true
		}
		return len(n.Parts) == 3 &&
			nameMatches(n.Parts[0].Value, t.Catalog) &&
			nameMatches(n.Parts[1].Value, t.Schema) &&
			nameMatches(n.Parts[2].Value, t.Name)
	case *ast.TableName:
		parts := n.Parts
		if len(parts) == 1 && nameMatches(parts[0].Value, t.Name) {
			return true
		}
		return len(parts) == 3 &&
			nameMatches(parts[0].Value, t.Catalog) &&
			nameMatches(parts[1].Value, t.Schema) &&
			nameMatches(parts[2].Value, t.Name)
	default:
		for _, child := range childrenOf(node) {
			if subtreeMentionsTable(child, t) {
				return true
			}
		}
		return false
	}
}

// childrenOf 返回节点的全部子节点（表达式/FROM/查询），通用遍历用。
func childrenOf(node ast.Node) []ast.Node {
	var out []ast.Node
	add := func(n ast.Node) {
		if n != nil {
			out = append(out, n)
		}
	}
	switch n := node.(type) {
	case *ast.Select:
		for _, it := range n.Items {
			if it.Expr != nil {
				add(it.Expr)
			}
		}
		for _, r := range n.From {
			add(r)
		}
		add(n.Where)
		for _, g := range n.GroupBy {
			addGroupChildren(g, &out)
		}
		add(n.Having)
	case *ast.OrderBy:
		add(n.Query)
		for _, it := range n.Items {
			add(it.Expr)
		}
		add(n.Limit)
		add(n.Offset)
		add(n.Fetch)
	case *ast.With:
		for _, it := range n.Items {
			add(it.Body)
		}
		add(n.Body)
	case *ast.SetOp:
		add(n.Left)
		add(n.Right)
	case *ast.Values:
		for _, row := range n.Rows {
			for _, e := range row {
				add(e)
			}
		}
	case *ast.TableName:
		// 名字段不算子节点（由专门的分支处理）
	case *ast.Derived:
		add(n.Query)
	case *ast.Join:
		add(n.Left)
		add(n.Right)
		add(n.On)
	case *ast.FromParen:
		add(n.Ref)
	case *ast.Unnest:
		add(n.Expr)
	case *ast.Ident, *ast.Star, *ast.Literal, *ast.Param:
		// 叶子
	case *ast.Unary:
		add(n.X)
	case *ast.Binary:
		add(n.L)
		add(n.R)
	case *ast.IsPred:
		add(n.X)
		add(n.R)
	case *ast.Between:
		add(n.X)
		add(n.Low)
		add(n.High)
	case *ast.InPred:
		add(n.X)
		for _, e := range n.List {
			add(e)
		}
		if n.Sub != nil {
			add(n.Sub)
		}
	case *ast.Like:
		add(n.X)
		add(n.Pattern)
		add(n.Esc)
	case *ast.Exists:
		add(n.Query)
	case *ast.Subquery:
		add(n.Query)
	case *ast.Call:
		for _, e := range n.Args {
			add(e)
		}
		if n.Over != nil {
			for _, e := range n.Over.PartitionBy {
				add(e)
			}
			for _, it := range n.Over.Order {
				add(it.Expr)
			}
			if n.Over.Frame != nil {
				add(n.Over.Frame.Start.Offset)
				add(n.Over.Frame.End.Offset)
			}
		}
	case *ast.Case:
		add(n.Operand)
		for _, w := range n.Whens {
			add(w.Cond)
			add(w.Then)
		}
		add(n.Else)
	case *ast.Cast:
		add(n.X)
	case *ast.Collate:
		add(n.X)
	}
	return out
}

func addGroupChildren(g ast.GroupItem, out *[]ast.Node) {
	add := func(n ast.Node) {
		if n != nil {
			*out = append(*out, n)
		}
	}
	add(g.Expr)
	for _, e := range g.Exprs {
		add(e)
	}
	for _, s := range g.Sets {
		for _, e := range s {
			add(e)
		}
	}
}

// rewriteExpressionWalk 表达式位置遍历：进入子查询（查询级处理），其余
// 递归操作数。
func (rw *Rewriter) rewriteExpr(e ast.Expr, c *ctx, scopes cteScopes) (ast.Expr, error) {
	if e == nil {
		return nil, nil
	}
	switch n := e.(type) {
	case *ast.Subquery:
		q, err := rw.rewriteQuery(ast.AsStatement(n.Query), c, scopes)
		if err != nil {
			return nil, err
		}
		if sameQuery(ast.AsQuery(q), n.Query) {
			return n, nil
		}
		return &ast.Subquery{Pos: n.Pos, Query: ast.AsQuery(q)}, nil
	case *ast.Exists:
		q, err := rw.rewriteQuery(ast.AsStatement(n.Query), c, scopes)
		if err != nil {
			return nil, err
		}
		if sameQuery(ast.AsQuery(q), n.Query) {
			return n, nil
		}
		return &ast.Exists{Pos: n.Pos, Query: ast.AsQuery(q)}, nil
	case *ast.InPred:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		list, err := rw.rewriteExprList(n.List, c, scopes)
		if err != nil {
			return nil, err
		}
		var sub *ast.Subquery
		if n.Sub != nil {
			q, err := rw.rewriteQuery(ast.AsStatement(n.Sub.Query), c, scopes)
			if err != nil {
				return nil, err
			}
			if !sameQuery(ast.AsQuery(q), n.Sub.Query) {
				sub = &ast.Subquery{Pos: n.Sub.Pos, Query: ast.AsQuery(q)}
			} else {
				sub = n.Sub
			}
		}
		if x == n.X && sameExprList(list, n.List) && sub == n.Sub {
			return n, nil
		}
		return &ast.InPred{Pos: n.Pos, X: x, Neg: n.Neg, List: list, Sub: sub}, nil
	case *ast.Call:
		args, err := rw.rewriteExprList(n.Args, c, scopes)
		if err != nil {
			return nil, err
		}
		var over *ast.Window
		if n.Over != nil {
			w, err := rw.rewriteWindow(n.Over, c, scopes)
			if err != nil {
				return nil, err
			}
			over = w
		}
		if sameExprList(args, n.Args) && over == n.Over {
			return n, nil
		}
		return &ast.Call{Pos: n.Pos, Name: n.Name, Distinct: n.Distinct, Star: n.Star,
			Args: args, Over: over}, nil
	case *ast.Case:
		operand, err := rw.rewriteExprOpt(n.Operand, c, scopes)
		if err != nil {
			return nil, err
		}
		whens := make([]ast.When, len(n.Whens))
		changed := operand != n.Operand
		for i, w := range n.Whens {
			cond, err := rw.rewriteExpr(w.Cond, c, scopes)
			if err != nil {
				return nil, err
			}
			then, err := rw.rewriteExpr(w.Then, c, scopes)
			if err != nil {
				return nil, err
			}
			whens[i] = ast.When{Cond: cond, Then: then}
			changed = changed || cond != w.Cond || then != w.Then
		}
		els, err := rw.rewriteExprOpt(n.Else, c, scopes)
		if err != nil {
			return nil, err
		}
		changed = changed || els != n.Else
		if !changed {
			return n, nil
		}
		return &ast.Case{Pos: n.Pos, Operand: operand, Whens: whens, Else: els}, nil
	case *ast.Cast:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		if x == n.X {
			return n, nil
		}
		return &ast.Cast{Pos: n.Pos, X: x, Type: n.Type}, nil
	case *ast.Collate:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		if x == n.X {
			return n, nil
		}
		return &ast.Collate{Pos: n.Pos, X: x, Collation: n.Collation}, nil
	case *ast.Unary:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		if x == n.X {
			return n, nil
		}
		return &ast.Unary{Pos: n.Pos, Op: n.Op, X: x}, nil
	case *ast.Binary:
		l, err := rw.rewriteExpr(n.L, c, scopes)
		if err != nil {
			return nil, err
		}
		r, err := rw.rewriteExpr(n.R, c, scopes)
		if err != nil {
			return nil, err
		}
		if l == n.L && r == n.R {
			return n, nil
		}
		return &ast.Binary{Pos: n.Pos, Op: n.Op, L: l, R: r}, nil
	case *ast.IsPred:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		var r ast.Expr
		if n.R != nil {
			r, err = rw.rewriteExpr(n.R, c, scopes)
			if err != nil {
				return nil, err
			}
		}
		if x == n.X && r == n.R {
			return n, nil
		}
		return &ast.IsPred{Pos: n.Pos, X: x, Neg: n.Neg, What: n.What, R: r}, nil
	case *ast.Between:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		low, err := rw.rewriteExpr(n.Low, c, scopes)
		if err != nil {
			return nil, err
		}
		high, err := rw.rewriteExpr(n.High, c, scopes)
		if err != nil {
			return nil, err
		}
		if x == n.X && low == n.Low && high == n.High {
			return n, nil
		}
		return &ast.Between{Pos: n.Pos, X: x, Low: low, High: high, Neg: n.Neg, Symmetric: n.Symmetric}, nil
	case *ast.Like:
		x, err := rw.rewriteExpr(n.X, c, scopes)
		if err != nil {
			return nil, err
		}
		pattern, err := rw.rewriteExpr(n.Pattern, c, scopes)
		if err != nil {
			return nil, err
		}
		var esc ast.Expr
		if n.Esc != nil {
			esc, err = rw.rewriteExpr(n.Esc, c, scopes)
			if err != nil {
				return nil, err
			}
		}
		if x == n.X && pattern == n.Pattern && esc == n.Esc {
			return n, nil
		}
		return &ast.Like{Pos: n.Pos, X: x, Pattern: pattern, Neg: n.Neg, Kind: n.Kind, Esc: esc}, nil
	default:
		return e, nil
	}
}

func (rw *Rewriter) rewriteExprOpt(e ast.Expr, c *ctx, scopes cteScopes) (ast.Expr, error) {
	if e == nil {
		return nil, nil
	}
	return rw.rewriteExpr(e, c, scopes)
}

func (rw *Rewriter) rewriteExprList(es []ast.Expr, c *ctx, scopes cteScopes) ([]ast.Expr, error) {
	out := make([]ast.Expr, len(es))
	for i, e := range es {
		ne, err := rw.rewriteExpr(e, c, scopes)
		if err != nil {
			return nil, err
		}
		out[i] = ne
	}
	return out, nil
}

func (rw *Rewriter) rewriteWindow(w *ast.Window, c *ctx, scopes cteScopes) (*ast.Window, error) {
	part, err := rw.rewriteExprList(w.PartitionBy, c, scopes)
	if err != nil {
		return nil, err
	}
	order, err := rw.rewriteOrderItems(w.Order, c, scopes)
	if err != nil {
		return nil, err
	}
	frame := w.Frame
	if w.Frame != nil {
		startOff, err := rw.rewriteExprOpt(w.Frame.Start.Offset, c, scopes)
		if err != nil {
			return nil, err
		}
		endOff, err := rw.rewriteExprOpt(w.Frame.End.Offset, c, scopes)
		if err != nil {
			return nil, err
		}
		if startOff != w.Frame.Start.Offset || endOff != w.Frame.End.Offset {
			nf := *w.Frame
			nf.Start.Offset = startOff
			nf.End.Offset = endOff
			frame = &nf
		}
	}
	if sameExprList(part, w.PartitionBy) && sameOrderItems(order, w.Order) && frame == w.Frame {
		return w, nil
	}
	return &ast.Window{Pos: w.Pos, PartitionBy: part, Order: order, Frame: frame}, nil
}

// resolveTableReference 名字解析 + 注入（对齐 Java 语义：1 段名 CTE 优先、
// 多候选歧义拒绝、3 段精确匹配；PG 跳过 2 段名）。
func (rw *Rewriter) resolveTableReference(ref *ast.TableName, c *ctx, scopes cteScopes) (ast.TableRef, error) {
	parts := ref.Parts
	switch {
	case len(parts) == 1:
		name := parts[0].Value
		if isVisibleCte(name, scopes) {
			return ref, nil
		}
		var candidates []*metadata.Table
		for _, t := range rw.cfg.Tables {
			if nameMatches(name, t.Name) {
				candidates = append(candidates, t)
			}
		}
		if len(candidates) == 0 {
			return ref, nil
		}
		if len(candidates) > 1 {
			var qualified []string
			for _, t := range candidates {
				qualified = append(qualified, t.QualifiedName())
			}
			return nil, maskerr.Errorf(maskerr.ValidationError,
				"unqualified table reference '%s' matches multiple declared tables [%s]; qualify the reference so the row filter decision is unambiguous",
				name, strings.Join(sortQualified(qualified), ", "))
		}
		return rw.injectIfFiltered(candidates[0], ref, c)
	case len(parts) == 3:
		for _, t := range rw.cfg.Tables {
			if nameMatches(parts[0].Value, t.Catalog) &&
				nameMatches(parts[1].Value, t.Schema) &&
				nameMatches(parts[2].Value, t.Name) {
				return rw.injectIfFiltered(t, ref, c)
			}
		}
		return ref, nil
	default:
		// 2 段名（PG 恒跳过）与其他形态留给后续校验
		return ref, nil
	}
}

func isVisibleCte(name string, scopes cteScopes) bool {
	for _, scope := range scopes {
		if scope[strings.ToLower(name)] {
			return true
		}
	}
	return false
}

// nameMatches PG：解析期未引号已折小写，因此比较是大小写敏感 equals。
func nameMatches(reference, declared string) bool {
	return reference == declared
}

// injectIfFiltered 命中受控表时构造派生表（每个引用位独立构造，不共享
// 子树——模板条件是只读共享，外层壳全新建）。
func (rw *Rewriter) injectIfFiltered(t *metadata.Table, ref *ast.TableName, c *ctx) (ast.TableRef, error) {
	tmpl := rw.registry.ConditionTemplateOf(t.Catalog, t.Schema, t.Name)
	if tmpl == nil {
		return ref, nil
	}
	inner := &ast.Select{
		Pos:   ref.Pos,
		Items: []ast.Item{{Star: true}},
		From:  []ast.TableRef{&ast.TableName{Pos: ref.Pos, Parts: ref.Parts}},
		Where: tmpl.Cond,
	}
	alias := &ast.TableAlias{Name: ref.Parts[len(ref.Parts)-1]}
	if ref.Alias != nil {
		alias = &ast.TableAlias{Name: ref.Alias.Name, Columns: ref.Alias.Columns}
	}
	c.injections++
	return &ast.Derived{Pos: ref.Pos, Query: inner, Alias: alias}, nil
}

// rejectQualifiedColumnReferences 注入后拒绝 4 段限定列引用
// （catalog.schema.table.column 匹配受控表）。
func rejectQualifiedColumnReferences(rw *Rewriter, parsed ast.Statement, c *ctx) error {
	for _, t := range rw.cfg.Tables {
		if !rw.registry.IsControlled(t.Catalog, t.Schema, t.Name) {
			continue
		}
		if hasQualifiedColumnReference(parsed, t) {
			return maskerr.Errorf(maskerr.UnsupportedStatement,
				"statement references filtered table '%s' with fully qualified columns, which an injected row filter "+
					"would break; alias the table and qualify columns with it instead", t.QualifiedName())
		}
	}
	return nil
}

func hasQualifiedColumnReference(node ast.Node, t *metadata.Table) bool {
	if node == nil {
		return false
	}
	switch n := node.(type) {
	case *ast.Ident:
		return len(n.Parts) == 4 &&
			nameMatches(n.Parts[0].Value, t.Catalog) &&
			nameMatches(n.Parts[1].Value, t.Schema) &&
			nameMatches(n.Parts[2].Value, t.Name)
	default:
		for _, child := range childrenOf(node) {
			if hasQualifiedColumnReference(child, t) {
				return true
			}
		}
		return false
	}
}

// ---------------- 同一性助手（copy-on-write 判定） ----------------

func sameQuery(a, b ast.Query) bool { return a == b }

func sameExprList(a, b []ast.Expr) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func sameOrderItems(a, b []ast.OrderItem) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].Expr != b[i].Expr || a[i].Dir != b[i].Dir || a[i].Nulls != b[i].Nulls {
			return false
		}
	}
	return true
}

func sameItems(a, b []ast.OrderItem) bool { return sameOrderItems(a, b) }
