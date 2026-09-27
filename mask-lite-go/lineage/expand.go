// Package lineage 在 AST 级做 CTE 内联、名字解析与输出列来源
// （origins）推导，替代 Java 版对 Calcite RelMetadataQuery 的依赖。
// fail-closed：递归 CTE 与任何未知构造都报 LINEAGE_UNKNOWN。
package lineage

import (
	"io.masklite/go/ast"
	"io.masklite/go/maskerr"
)

// Expand 内联非递归 CTE 为派生表（PG 作用域：内层遮蔽外层、只见前序
// 兄弟、自引用/环 → LINEAGE_UNKNOWN）。不修改输入树；展开体在各引用位
// 间共享只读（下游不再变异）。
func Expand(stmt ast.Statement) (ast.Statement, error) {
	return expandQuery(stmt, &expander{})
}

type expander struct {
	// scopes 栈：每层有已完成 CTE 与一个 in-flight 名（环检测）。
	scopes []*expScope
}

type expScope struct {
	completed map[string]*cteEntry
	order     []string
	inFlight  string
}

type cteEntry struct {
	name    string
	body    ast.Query
	columns []ast.IdentPart
}

func (e *expander) lookup(name string) (*cteEntry, error) {
	for i := len(e.scopes) - 1; i >= 0; i-- {
		sc := e.scopes[i]
		if cte, ok := sc.completed[name]; ok {
			return cte, nil
		}
		if sc.inFlight != "" && sc.inFlight == name {
			return nil, maskerr.Errorf(maskerr.LineageUnknown,
				"cannot trace lineage through recursive CTE '%s'; recursive common table expressions are not supported in this version", name)
		}
	}
	return nil, nil
}

func (e *expander) push() *expScope {
	sc := &expScope{completed: map[string]*cteEntry{}}
	e.scopes = append(e.scopes, sc)
	return sc
}

func (e *expander) pop() { e.scopes = e.scopes[:len(e.scopes)-1] }

// expandQuery 语句/查询级展开。
func expandQuery(node ast.Statement, e *expander) (ast.Statement, error) {
	switch n := node.(type) {
	case *ast.Select:
		return expandSelect(n, e)
	case *ast.With:
		return expandWith(n, e)
	case *ast.OrderBy:
		// 归一 ORDER_BY(WITH)：把 ORDER BY 移进 WITH 体，使 ORDER BY 中的
		// 子查询能看到 CTE（对齐 Java expandOrderBy）。
		if with, ok := n.Query.(*ast.With); ok {
			inner := &ast.OrderBy{Pos: n.Pos, Query: with.Body, Items: n.Items,
				Limit: n.Limit, LimitAll: n.LimitAll, Offset: n.Offset, Fetch: n.Fetch}
			merged := &ast.With{Pos: with.Pos, Recursive: with.Recursive, Items: with.Items, Body: inner}
			return expandQuery(merged, e)
		}
		q, err := expandQueryNode(n.Query, e)
		if err != nil {
			return nil, err
		}
		items, err := expandOrderItems(n.Items, e)
		if err != nil {
			return nil, err
		}
		offset, err := expandExprOpt(n.Offset, e)
		if err != nil {
			return nil, err
		}
		fetch, err := expandExprOpt(n.Fetch, e)
		if err != nil {
			return nil, err
		}
		limit, err := expandExprOpt(n.Limit, e)
		if err != nil {
			return nil, err
		}
		return &ast.OrderBy{Pos: n.Pos, Query: q, Items: items,
			Limit: limit, LimitAll: n.LimitAll, Offset: offset, Fetch: fetch}, nil
	case *ast.SetOp:
		left, err := expandQuery(ast.AsStatement(n.Left), e)
		if err != nil {
			return nil, err
		}
		right, err := expandQuery(ast.AsStatement(n.Right), e)
		if err != nil {
			return nil, err
		}
		return &ast.SetOp{Pos: n.Pos, Op: n.Op, All: n.All, Left: ast.AsQuery(left), Right: ast.AsQuery(right)}, nil
	case *ast.Values:
		rows := make([][]ast.Expr, len(n.Rows))
		for i, row := range n.Rows {
			nr := make([]ast.Expr, len(row))
			for j, cell := range row {
				ne, err := expandExpr(cell, e)
				if err != nil {
					return nil, err
				}
				nr[j] = ne
			}
			rows[i] = nr
		}
		return &ast.Values{Pos: n.Pos, Rows: rows}, nil
	default:
		return node, nil
	}
}

// expandQueryNode 查询位置的展开（FROM 派生表/子查询内部）。
func expandQueryNode(q ast.Query, e *expander) (ast.Query, error) {
	out, err := expandQuery(q.(ast.Statement), e)
	if err != nil {
		return nil, err
	}
	return out.(ast.Query), nil
}

func expandWith(n *ast.With, e *expander) (ast.Statement, error) {
	sc := e.push()
	defer e.pop()
	items := make([]ast.WithItem, len(n.Items))
	for i, item := range n.Items {
		sc.inFlight = item.Name.Value
		body, err := expandQueryNode(item.Body, e)
		if err != nil {
			return nil, err
		}
		sc.inFlight = ""
		sc.completed[item.Name.Value] = &cteEntry{name: item.Name.Value, body: body, columns: item.Columns}
		items[i] = ast.WithItem{Pos: item.Pos, Name: item.Name, Columns: item.Columns, Body: body}
	}
	body, err := expandQueryNode(n.Body, e)
	if err != nil {
		return nil, err
	}
	return &ast.With{Pos: n.Pos, Recursive: n.Recursive, Items: items, Body: body}, nil
}

func expandSelect(n *ast.Select, e *expander) (ast.Statement, error) {
	out := &ast.Select{Pos: n.Pos, Distinct: n.Distinct}
	for _, ref := range n.From {
		nr, err := expandFromItem(ref, e)
		if err != nil {
			return nil, err
		}
		out.From = append(out.From, nr)
	}
	for _, it := range n.Items {
		if it.Star {
			out.Items = append(out.Items, it)
			continue
		}
		ne, err := expandExpr(it.Expr, e)
		if err != nil {
			return nil, err
		}
		out.Items = append(out.Items, ast.Item{Expr: ne, Alias: it.Alias})
	}
	if n.Where != nil {
		w, err := expandExpr(n.Where, e)
		if err != nil {
			return nil, err
		}
		out.Where = w
	}
	for _, g := range n.GroupBy {
		ng, err := expandGroupItem(g, e)
		if err != nil {
			return nil, err
		}
		out.GroupBy = append(out.GroupBy, ng)
	}
	if n.Having != nil {
		h, err := expandExpr(n.Having, e)
		if err != nil {
			return nil, err
		}
		out.Having = h
	}
	return out, nil
}

func expandFromItem(ref ast.TableRef, e *expander) (ast.TableRef, error) {
	switch n := ref.(type) {
	case *ast.TableName:
		if len(n.Parts) == 1 && !n.Parts[0].Quoted {
			cte, err := e.lookup(n.Parts[0].Value)
			if err != nil {
				return nil, err
			}
			if cte != nil {
				alias := &ast.TableAlias{Name: n.Parts[0], Columns: cte.columns}
				if n.Alias != nil {
					alias = &ast.TableAlias{Name: n.Alias.Name, Columns: cte.columns}
					if len(n.Alias.Columns) > 0 {
						alias.Columns = n.Alias.Columns
					}
				}
				return &ast.Derived{Pos: n.Pos, Query: cte.body, Alias: alias}, nil
			}
		}
		return n, nil
	case *ast.FromParen:
		inner, err := expandFromItem(n.Ref, e)
		if err != nil {
			return nil, err
		}
		return &ast.FromParen{Pos: n.Pos, Ref: inner, Alias: n.Alias}, nil
	case *ast.Derived:
		q, err := expandQueryNode(n.Query, e)
		if err != nil {
			return nil, err
		}
		return &ast.Derived{Pos: n.Pos, Query: q, Alias: n.Alias, Lateral: n.Lateral}, nil
	case *ast.Join:
		left, err := expandFromItem(n.Left, e)
		if err != nil {
			return nil, err
		}
		right, err := expandFromItem(n.Right, e)
		if err != nil {
			return nil, err
		}
		var on ast.Expr
		if n.On != nil {
			on, err = expandExpr(n.On, e)
			if err != nil {
				return nil, err
			}
		}
		return &ast.Join{Pos: n.Pos, Natural: n.Natural, Kind: n.Kind, Left: left, Right: right, On: on, Using: n.Using}, nil
	case *ast.Unnest:
		ex, err := expandExpr(n.Expr, e)
		if err != nil {
			return nil, err
		}
		return &ast.Unnest{Pos: n.Pos, Expr: ex, Alias: n.Alias}, nil
	default:
		return ref, nil
	}
}

func expandGroupItem(g ast.GroupItem, e *expander) (ast.GroupItem, error) {
	switch g.Op {
	case ast.GroupSimple:
		ne, err := expandExpr(g.Expr, e)
		if err != nil {
			return ast.GroupItem{}, err
		}
		return ast.GroupItem{Op: g.Op, Expr: ne}, nil
	case ast.GroupRollup, ast.GroupCube:
		exprs, err := expandExprList(g.Exprs, e)
		if err != nil {
			return ast.GroupItem{}, err
		}
		return ast.GroupItem{Op: g.Op, Exprs: exprs}, nil
	default:
		var sets [][]ast.Expr
		for _, s := range g.Sets {
			ns, err := expandExprList(s, e)
			if err != nil {
				return ast.GroupItem{}, err
			}
			sets = append(sets, ns)
		}
		return ast.GroupItem{Op: g.Op, Sets: sets}, nil
	}
}

func expandOrderItems(items []ast.OrderItem, e *expander) ([]ast.OrderItem, error) {
	out := make([]ast.OrderItem, len(items))
	for i, it := range items {
		ne, err := expandExpr(it.Expr, e)
		if err != nil {
			return nil, err
		}
		out[i] = ast.OrderItem{Expr: ne, Dir: it.Dir, Nulls: it.Nulls}
	}
	return out, nil
}

// expandExpr 表达式遍历：进入其中的子查询（查询级展开）。
func expandExpr(expr ast.Expr, e *expander) (ast.Expr, error) {
	if expr == nil {
		return nil, nil
	}
	switch n := expr.(type) {
	case *ast.Subquery:
		q, err := expandQueryNode(n.Query, e)
		if err != nil {
			return nil, err
		}
		return &ast.Subquery{Pos: n.Pos, Query: q}, nil
	case *ast.Exists:
		q, err := expandQueryNode(n.Query, e)
		if err != nil {
			return nil, err
		}
		return &ast.Exists{Pos: n.Pos, Query: q}, nil
	case *ast.InPred:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		list, err := expandExprList(n.List, e)
		if err != nil {
			return nil, err
		}
		var sub *ast.Subquery
		if n.Sub != nil {
			q, err := expandQueryNode(n.Sub.Query, e)
			if err != nil {
				return nil, err
			}
			sub = &ast.Subquery{Pos: n.Sub.Pos, Query: q}
		}
		return &ast.InPred{Pos: n.Pos, X: x, Neg: n.Neg, List: list, Sub: sub}, nil
	case *ast.Call:
		args, err := expandExprList(n.Args, e)
		if err != nil {
			return nil, err
		}
		var over *ast.Window
		if n.Over != nil {
			part, err := expandExprList(n.Over.PartitionBy, e)
			if err != nil {
				return nil, err
			}
			order, err := expandOrderItems(n.Over.Order, e)
			if err != nil {
				return nil, err
			}
			var frame *ast.Frame
			if n.Over.Frame != nil {
				startOff, err := expandExprOpt(n.Over.Frame.Start.Offset, e)
				if err != nil {
					return nil, err
				}
				endOff, err := expandExprOpt(n.Over.Frame.End.Offset, e)
				if err != nil {
					return nil, err
				}
				nf := *n.Over.Frame
				nf.Start.Offset = startOff
				nf.End.Offset = endOff
				frame = &nf
			}
			over = &ast.Window{Pos: n.Over.Pos, PartitionBy: part, Order: order, Frame: frame}
		}
		return &ast.Call{Pos: n.Pos, Name: n.Name, Distinct: n.Distinct, Star: n.Star, Args: args, Over: over}, nil
	case *ast.Case:
		operand, err := expandExprOpt(n.Operand, e)
		if err != nil {
			return nil, err
		}
		whens := make([]ast.When, len(n.Whens))
		for i, w := range n.Whens {
			cond, err := expandExpr(w.Cond, e)
			if err != nil {
				return nil, err
			}
			then, err := expandExpr(w.Then, e)
			if err != nil {
				return nil, err
			}
			whens[i] = ast.When{Cond: cond, Then: then}
		}
		els, err := expandExprOpt(n.Else, e)
		if err != nil {
			return nil, err
		}
		return &ast.Case{Pos: n.Pos, Operand: operand, Whens: whens, Else: els}, nil
	case *ast.Cast:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		return &ast.Cast{Pos: n.Pos, X: x, Type: n.Type}, nil
	case *ast.Collate:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		return &ast.Collate{Pos: n.Pos, X: x, Collation: n.Collation}, nil
	case *ast.Unary:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		return &ast.Unary{Pos: n.Pos, Op: n.Op, X: x}, nil
	case *ast.Binary:
		l, err := expandExpr(n.L, e)
		if err != nil {
			return nil, err
		}
		r, err := expandExpr(n.R, e)
		if err != nil {
			return nil, err
		}
		return &ast.Binary{Pos: n.Pos, Op: n.Op, L: l, R: r}, nil
	case *ast.IsPred:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		var r ast.Expr
		if n.R != nil {
			r, err = expandExpr(n.R, e)
			if err != nil {
				return nil, err
			}
		}
		return &ast.IsPred{Pos: n.Pos, X: x, Neg: n.Neg, What: n.What, R: r}, nil
	case *ast.Between:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		low, err := expandExpr(n.Low, e)
		if err != nil {
			return nil, err
		}
		high, err := expandExpr(n.High, e)
		if err != nil {
			return nil, err
		}
		return &ast.Between{Pos: n.Pos, X: x, Low: low, High: high, Neg: n.Neg, Symmetric: n.Symmetric}, nil
	case *ast.Like:
		x, err := expandExpr(n.X, e)
		if err != nil {
			return nil, err
		}
		pattern, err := expandExpr(n.Pattern, e)
		if err != nil {
			return nil, err
		}
		var esc ast.Expr
		if n.Esc != nil {
			esc, err = expandExpr(n.Esc, e)
			if err != nil {
				return nil, err
			}
		}
		return &ast.Like{Pos: n.Pos, X: x, Pattern: pattern, Neg: n.Neg, Kind: n.Kind, Esc: esc}, nil
	default:
		return expr, nil
	}
}

func expandExprOpt(e ast.Expr, ex *expander) (ast.Expr, error) {
	if e == nil {
		return nil, nil
	}
	return expandExpr(e, ex)
}

func expandExprList(es []ast.Expr, e *expander) ([]ast.Expr, error) {
	out := make([]ast.Expr, len(es))
	for i, x := range es {
		ne, err := expandExpr(x, e)
		if err != nil {
			return nil, err
		}
		out[i] = ne
	}
	return out, nil
}
