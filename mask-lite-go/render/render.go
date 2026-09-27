// Package render 把 AST 渲染回 SQL。排版对齐 Calcite PG unparse（零缩进、
// 子句关键字前换行、函数名规范大写、LIMIT→FETCH NEXT、BETWEEN ASYMMETRIC），
// 使产物能与 Java 版输出逐文件 diff（差分是开发仪器；验收标准是语义等价）。
package render

import (
	"strings"

	"io.masklite/go/ast"
)

// Statement 渲染语句级节点。
func Statement(stmt ast.Statement) string {
	switch n := stmt.(type) {
	case *ast.Select:
		return selectStr(n)
	case *ast.OrderBy:
		return orderByStr(n)
	case *ast.With:
		return withStr(n)
	case *ast.SetOp:
		return setOpStr(n)
	case *ast.Values:
		return valuesStr(n)
	default:
		return ""
	}
}

// Query 渲染查询级节点。
func Query(q ast.Query) string { return Statement(q.(ast.Statement)) }

func selectStr(n *ast.Select) string {
	var b strings.Builder
	b.WriteString("SELECT ")
	if n.Distinct {
		b.WriteString("DISTINCT ")
	}
	items := make([]string, 0, len(n.Items))
	for _, it := range n.Items {
		items = append(items, itemStr(it))
	}
	b.WriteString(strings.Join(items, ", "))
	if len(n.From) > 0 {
		b.WriteString("\nFROM ")
		refs := make([]string, 0, len(n.From))
		for _, r := range n.From {
			refs = append(refs, tableRefStr(r))
		}
		b.WriteString(strings.Join(refs, ",\n"))
	}
	if n.Where != nil {
		b.WriteString("\nWHERE ")
		b.WriteString(Expr(n.Where))
	}
	if len(n.GroupBy) > 0 {
		gs := make([]string, 0, len(n.GroupBy))
		for _, g := range n.GroupBy {
			gs = append(gs, groupItemStr(g))
		}
		b.WriteString("\nGROUP BY ")
		b.WriteString(strings.Join(gs, ", "))
	}
	if n.Having != nil {
		b.WriteString("\nHAVING ")
		b.WriteString(Expr(n.Having))
	}
	return b.String()
}

func itemStr(it ast.Item) string {
	if it.Star {
		if len(it.Prefix) > 0 {
			return identPartsStr(it.Prefix) + ".*"
		}
		return "*"
	}
	s := Expr(it.Expr)
	if it.Alias != nil {
		s += " AS " + Ident(it.Alias.Value)
	}
	return s
}

func groupItemStr(g ast.GroupItem) string {
	switch g.Op {
	case ast.GroupRollup:
		return "ROLLUP(" + exprListStr(g.Exprs) + ")"
	case ast.GroupCube:
		return "CUBE(" + exprListStr(g.Exprs) + ")"
	case ast.GroupSets:
		sets := make([]string, 0, len(g.Sets))
		for _, s := range g.Sets {
			sets = append(sets, "("+exprListStr(s)+")")
		}
		return "GROUPING SETS (" + strings.Join(sets, ", ") + ")"
	default:
		return Expr(g.Expr)
	}
}

func tableRefStr(r ast.TableRef) string {
	switch n := r.(type) {
	case *ast.TableName:
		s := identPartsStr(n.Parts)
		if n.Alias != nil {
			s += aliasStr(n.Alias)
		}
		return s
	case *ast.Derived:
		s := ""
		if n.Lateral {
			s += "LATERAL "
		}
		s += "(" + Query(n.Query) + ")"
		if n.Alias != nil {
			s += aliasStr(n.Alias)
		}
		return s
	case *ast.FromParen:
		s := "(" + tableRefStr(n.Ref) + ")"
		if n.Alias != nil {
			s += aliasStr(n.Alias)
		}
		return s
	case *ast.Unnest:
		s := "UNNEST(" + Expr(n.Expr) + ")"
		if n.Alias != nil {
			s += aliasStr(n.Alias)
		}
		return s
	case *ast.Join:
		left := tableRefStr(n.Left)
		right := tableRefStr(n.Right)
		kw := "INNER JOIN"
		switch n.Kind {
		case ast.JoinLeft:
			kw = "LEFT JOIN"
		case ast.JoinRight:
			kw = "RIGHT JOIN"
		case ast.JoinFull:
			kw = "FULL JOIN"
		case ast.JoinCross:
			kw = "CROSS JOIN"
		}
		if n.Natural {
			kw = "NATURAL " + kw
		}
		s := left + "\n" + kw + " " + right
		if n.On != nil {
			s += " ON " + Expr(n.On)
		} else if len(n.Using) > 0 {
			s += " USING (" + identPartListStr(n.Using) + ")"
		}
		return s
	default:
		return ""
	}
}

func aliasStr(a *ast.TableAlias) string {
	s := " AS " + Ident(a.Name.Value)
	if len(a.Columns) > 0 {
		s += " (" + identPartListStr(a.Columns) + ")"
	}
	return s
}

func withStr(n *ast.With) string {
	var b strings.Builder
	b.WriteString("WITH ")
	if n.Recursive {
		b.WriteString("RECURSIVE ")
	}
	items := make([]string, 0, len(n.Items))
	for _, it := range n.Items {
		s := Ident(it.Name.Value)
		if len(it.Columns) > 0 {
			s += " (" + identPartListStr(it.Columns) + ")"
		}
		s += " AS (" + Query(it.Body) + ")"
		items = append(items, s)
	}
	b.WriteString(strings.Join(items, ", "))
	b.WriteString(" ")
	b.WriteString(Query(n.Body))
	return b.String()
}

func setOpStr(n *ast.SetOp) string {
	op := "UNION"
	switch n.Op {
	case ast.OpIntersect:
		op = "INTERSECT"
	case ast.OpExcept:
		op = "EXCEPT"
	}
	if n.All {
		op += " ALL"
	}
	return Query(n.Left) + "\n" + op + "\n" + Query(n.Right)
}

func valuesStr(n *ast.Values) string {
	rows := make([]string, 0, len(n.Rows))
	for _, row := range n.Rows {
		rows = append(rows, "("+exprListStr(row)+")")
	}
	return "VALUES " + strings.Join(rows, ", ")
}

func orderByStr(n *ast.OrderBy) string {
	s := Query(n.Query)
	if len(n.Items) > 0 {
		items := make([]string, 0, len(n.Items))
		for _, it := range n.Items {
			x := Expr(it.Expr)
			switch it.Dir {
			case ast.DirAsc:
				x += " ASC"
			case ast.DirDesc:
				x += " DESC"
			}
			switch it.Nulls {
			case ast.NullsFirst:
				x += " NULLS FIRST"
			case ast.NullsLast:
				x += " NULLS LAST"
			}
			items = append(items, x)
		}
		s += "\nORDER BY " + strings.Join(items, ", ")
	}
	if n.Offset != nil {
		s += "\nOFFSET " + Expr(n.Offset) + " ROWS"
	}
	if n.Limit != nil {
		s += "\nFETCH NEXT " + Expr(n.Limit) + " ROWS ONLY"
	}
	return s
}

func exprListStr(es []ast.Expr) string {
	ss := make([]string, 0, len(es))
	for _, e := range es {
		ss = append(ss, Expr(e))
	}
	return strings.Join(ss, ", ")
}

func identPartsStr(ps []ast.IdentPart) string {
	ss := make([]string, 0, len(ps))
	for _, p := range ps {
		ss = append(ss, Ident(p.Value))
	}
	return strings.Join(ss, ".")
}

func identPartListStr(ps []ast.IdentPart) string {
	ss := make([]string, 0, len(ps))
	for _, p := range ps {
		ss = append(ss, Ident(p.Value))
	}
	return strings.Join(ss, ", ")
}
