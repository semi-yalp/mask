package lineage

import (
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/config"
	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
	"io.masklite/go/policy"
)

// Status 是输出列血缘分类。
type Status int

const (
	// StatusResolved 至少有一个可解析的基表列来源。
	StatusResolved Status = iota
	// StatusNoOrigin 常量/参数/纯标量子查询——无来源，透传。
	StatusNoOrigin
	// StatusUnknown 无法安全溯源——整语句 fail-closed。
	StatusUnknown
)

// Origin 是一个基表列来源。
type Origin struct {
	Key     metadata.ColumnKey
	Derived bool
}

// Output 是一个输出列的血缘。
type Output struct {
	Ordinal int
	Name    string
	Status  Status
	Origins []Origin // StatusResolved 时非空，首现去重
}

// Analyzer 做输出列血缘分析（AST 级）。
type Analyzer struct {
	cfg    *config.LoadedConfig
	engine *policy.Engine
}

// NewAnalyzer 构造；engine 用于标量子查询的安全性校验（选择器语义）。
func NewAnalyzer(cfg *config.LoadedConfig, engine *policy.Engine) *Analyzer {
	return &Analyzer{cfg: cfg, engine: engine}
}

// ---------------- 内部模型 ----------------

type outCol struct {
	name    string
	origins []Origin
	unknown bool
}

type source struct {
	name  string          // 别名或表名（引用侧拼写）
	cols  []outCol
	table *metadata.Table // 基表时非 nil（schema.table.column 解析用）
}

type scope struct {
	parent  *scope
	sources []*source
	using   map[string][]Origin // USING 列 → 合并 origins
}

// Analyze 分析顶层语句的输出列血缘。
func (a *Analyzer) Analyze(stmt ast.Statement) ([]Output, error) {
	var cols []outCol
	var sc *scope
	var err error
	switch n := stmt.(type) {
	case *ast.Select:
		cols, sc, err = a.queryOutputs(n, nil)
	case *ast.OrderBy:
		cols, sc, err = a.queryOutputsInner(n.Query, nil)
		if err == nil {
			err = a.resolveOrderLenient(n.Items, cols, sc)
		}
	case *ast.With:
		// CTE 已内联（展开器保留 With 外壳）；直接分析体
		cols, sc, err = a.queryOutputsInner(n.Body, nil)
	case *ast.SetOp:
		cols, sc, err = a.queryOutputsInner(n, nil)
	case *ast.Values:
		cols = valuesOutputs(n)
	default:
		return nil, maskerr.New(maskerr.LineageUnknown, "cannot analyze statement kind")
	}
	if err != nil {
		return nil, err
	}
	out := make([]Output, 0, len(cols))
	for i, c := range cols {
		o := Output{Ordinal: i + 1, Name: c.name}
		switch {
		case c.unknown:
			o.Status = StatusUnknown
		case len(c.origins) == 0:
			o.Status = StatusNoOrigin
		default:
			o.Status = StatusResolved
			o.Origins = dedupeOrigins(c.origins)
		}
		out = append(out, o)
	}
	return out, nil
}

func dedupeOrigins(origins []Origin) []Origin {
	seen := map[metadata.ColumnKey]bool{}
	out := make([]Origin, 0, len(origins))
	for _, o := range origins {
		if seen[o.Key] {
			continue
		}
		seen[o.Key] = true
		out = append(out, o)
	}
	return out
}

// ---------------- 查询输出 ----------------

// queryOutputs 分析 Select 并返回其作用域（ORDER BY 宽松解析用）。
func (a *Analyzer) queryOutputs(n *ast.Select, parent *scope) ([]outCol, *scope, error) {
	sc, err := a.buildScope(n, parent)
	if err != nil {
		return nil, nil, err
	}
	// WHERE / GROUP BY / HAVING 的名字解析边界（不产出 origins）
	if n.Where != nil {
		if _, _, err := exprOrigins(n.Where, sc); err != nil {
			return nil, nil, err
		}
	}
	for _, g := range n.GroupBy {
		if err := resolveGroupItem(g, sc); err != nil {
			return nil, nil, err
		}
	}
	if n.Having != nil {
		if _, _, err := exprOrigins(n.Having, sc); err != nil {
			return nil, nil, err
		}
	}
	// 输出列
	var cols []outCol
	counter := 0
	firstName := ""
	for _, item := range n.Items {
		if item.Star {
			srcs, err := starSources(sc, item.Prefix)
			if err != nil {
				return nil, nil, err
			}
			for _, s := range srcs {
				for _, c := range s.cols {
					counter++
					if firstName == "" {
						firstName = c.name
					}
					cols = append(cols, c)
				}
			}
			continue
		}
		counter++
		name := itemName(item, counter)
		if firstName == "" {
			firstName = name
		}
		origins, hasSub, err := exprOrigins(item.Expr, sc)
		if err != nil {
			return nil, nil, err
		}
		if hasSub {
			// 两阶段：先安全校验（任何子查询输出命中策略 → 整语句
			// fail-closed），再把子查询位视作 NULL 重取 origins
			if err := a.checkSubqueriesSafe(item.Expr, sc); err != nil {
				return nil, nil, err
			}
		}
		cols = append(cols, outCol{name: name, origins: origins})
	}
	return cols, sc, nil
}

func (a *Analyzer) queryOutputsInner(q ast.Query, parent *scope) ([]outCol, *scope, error) {
	switch n := q.(type) {
	case *ast.Select:
		return a.queryOutputs(n, parent)
	case *ast.SetOp:
		left, _, err := a.queryOutputsInner(n.Left, parent)
		if err != nil {
			return nil, nil, err
		}
		right, _, err := a.queryOutputsInner(n.Right, parent)
		if err != nil {
			return nil, nil, err
		}
		if len(left) != len(right) {
			return nil, nil, maskerr.New(maskerr.ValidationError,
				"validation failed: set-operation branches have different column counts")
		}
		cols := make([]outCol, len(left))
		for i := range left {
			origins := append(append([]Origin{}, left[i].origins...), right[i].origins...)
			cols[i] = outCol{name: left[i].name, origins: origins,
				unknown: left[i].unknown || right[i].unknown}
		}
		return cols, nil, nil
	case *ast.Values:
		return valuesOutputs(n), nil, nil
	case *ast.OrderBy:
		cols, sc, err := a.queryOutputsInner(n.Query, parent)
		if err != nil {
			return nil, nil, err
		}
		if err := a.resolveOrderLenient(n.Items, cols, sc); err != nil {
			return nil, nil, err
		}
		return cols, sc, nil
	case *ast.With:
		// Expand 后不应出现；兜底按体处理
		return a.queryOutputsInner(n.Body, parent)
	default:
		return nil, nil, maskerr.New(maskerr.LineageUnknown,
			"cannot analyze this query shape; cannot rewrite this statement")
	}
}

func valuesOutputs(n *ast.Values) []outCol {
	if len(n.Rows) == 0 {
		return nil
	}
	cols := make([]outCol, len(n.Rows[0]))
	for i := range n.Rows[0] {
		cols[i] = outCol{name: "EXPR$" + itoa(i+1)}
	}
	return cols
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

// itemName 输出列名：别名 > 列引用尾段 > EXPR$N（N=展开后位置）。
func itemName(item ast.Item, ordinal int) string {
	if item.Alias != nil {
		return item.Alias.Value
	}
	if id, ok := item.Expr.(*ast.Ident); ok && len(id.Parts) > 0 {
		return id.Parts[len(id.Parts)-1].Value
	}
	return "EXPR$" + itoa(ordinal)
}

// starSources 展开 * / t.* 到来源列（FROM 声明序）。
func starSources(sc *scope, prefix []ast.IdentPart) ([]*source, error) {
	if len(prefix) == 0 {
		return sc.sources, nil
	}
	name := prefix[len(prefix)-1].Value
	for _, s := range sc.sources {
		if s.name == name {
			return []*source{s}, nil
		}
	}
	return nil, maskerr.Errorf(maskerr.ValidationError,
		"validation failed: table alias '%s' not found", name)
}

// ---------------- FROM 作用域 ----------------

func (a *Analyzer) buildScope(n *ast.Select, parent *scope) (*scope, error) {
	sc := &scope{parent: parent, using: map[string][]Origin{}}
	for _, ref := range n.From {
		if err := a.addSource(ref, sc); err != nil {
			return nil, err
		}
	}
	return sc, nil
}

func (a *Analyzer) addSource(ref ast.TableRef, sc *scope) error {
	switch n := ref.(type) {
	case *ast.TableName:
		t, err := a.resolveDeclaredTable(n.Parts)
		if err != nil {
			return err
		}
		name := n.Parts[len(n.Parts)-1].Value
		if n.Alias != nil {
			name = n.Alias.Name.Value
		}
		s := &source{name: name, table: t}
		for _, c := range t.Columns {
			s.cols = append(s.cols, outCol{
				name:    c.Name,
				origins: []Origin{{Key: metadata.Key(t.Catalog, t.Schema, t.Name, c.Name)}},
			})
		}
		sc.sources = append(sc.sources, s)
		return nil
	case *ast.Derived:
		innerParent := sc.parent // 非 lateral 派生表看不到兄弟 FROM 项
		if n.Lateral {
			innerParent = sc
		}
		inner, _, err := a.queryOutputsInner(n.Query, innerParent)
		if err != nil {
			return err
		}
		name := ""
		var renames []ast.IdentPart
		if n.Alias != nil {
			name = n.Alias.Name.Value
			renames = n.Alias.Columns
		}
		if len(renames) > 0 {
			if len(renames) != len(inner) {
				return maskerr.New(maskerr.ValidationError,
					"validation failed: derived table column alias list does not match column count")
			}
			for i := range inner {
				inner[i].name = renames[i].Value
			}
		}
		sc.sources = append(sc.sources, &source{name: name, cols: inner})
		return nil
	case *ast.Join:
		if err := a.addSource(n.Left, sc); err != nil {
			return err
		}
		if err := a.addSource(n.Right, sc); err != nil {
			return err
		}
		usingNames := n.Using
		if n.Natural {
			usingNames = naturalCommon(sc)
		}
		for _, u := range usingNames {
			var merged []Origin
			found := 0
			for _, s := range sc.sources {
				for _, c := range s.cols {
					if c.name == u.Value {
						merged = append(merged, c.origins...)
						found++
					}
				}
			}
			if found == 0 {
				return maskerr.Errorf(maskerr.ValidationError,
					"validation failed: USING column '%s' not found", u.Value)
			}
			sc.using[u.Value] = merged
		}
		if n.On != nil {
			if _, _, err := exprOrigins(n.On, sc); err != nil {
				return err
			}
		}
		return nil
	case *ast.FromParen:
		return a.addSource(n.Ref, sc)
	case *ast.Unnest:
		origins, hasSub, err := exprOrigins(n.Expr, sc)
		if err != nil {
			return err
		}
		if hasSub || len(origins) > 1 {
			sc.sources = append(sc.sources, &source{cols: []outCol{{name: "unnest", unknown: true}}})
			return nil
		}
		sc.sources = append(sc.sources, &source{cols: []outCol{{name: "unnest", origins: origins}}})
		return nil
	default:
		return maskerr.New(maskerr.LineageUnknown,
			"unsupported FROM clause shape; cannot rewrite this statement")
	}
}

func naturalCommon(sc *scope) []ast.IdentPart {
	if len(sc.sources) < 2 {
		return nil
	}
	left := sc.sources[len(sc.sources)-2]
	right := sc.sources[len(sc.sources)-1]
	var common []ast.IdentPart
	for _, lc := range left.cols {
		for _, rc := range right.cols {
			if lc.name == rc.name {
				common = append(common, ast.IdentPart{Value: lc.name})
			}
		}
	}
	return common
}

// resolveDeclaredTable 把 FROM 表名解析为声明表（1 段名多候选时取声明序
// 第一，对齐 search-path first-match；0 候选报 VALIDATION_ERROR）。
func (a *Analyzer) resolveDeclaredTable(parts []ast.IdentPart) (*metadata.Table, error) {
	refName := joinParts(parts)
	switch len(parts) {
	case 1:
		name := parts[0].Value
		for _, t := range a.cfg.Tables {
			if t.Name == name {
				return t, nil
			}
		}
	case 2:
		schema, name := parts[0].Value, parts[1].Value
		for _, t := range a.cfg.Tables {
			if t.Schema == schema && t.Name == name {
				return t, nil
			}
		}
	case 3:
		catalog, schema, name := parts[0].Value, parts[1].Value, parts[2].Value
		for _, t := range a.cfg.Tables {
			if t.Catalog == catalog && t.Schema == schema && t.Name == name {
				return t, nil
			}
		}
	}
	return nil, maskerr.Errorf(maskerr.ValidationError,
		"validation failed: table '%s' not found", refName)
}

func joinParts(parts []ast.IdentPart) string {
	ss := make([]string, 0, len(parts))
	for _, p := range parts {
		ss = append(ss, p.Value)
	}
	return strings.Join(ss, ".")
}

// ---------------- 列解析 ----------------

func (sc *scope) resolve(parts []ast.IdentPart) (outCol, error) {
	asWritten := joinParts(parts)
	switch len(parts) {
	case 1:
		name := parts[0].Value
		var matches []outCol
		for _, s := range sc.sources {
			for _, c := range s.cols {
				if c.name == name {
					matches = append(matches, c)
				}
			}
		}
		if len(matches) == 1 {
			return matches[0], nil
		}
		if len(matches) > 1 {
			if merged, ok := sc.using[name]; ok {
				return outCol{name: name, origins: merged}, nil
			}
			return outCol{}, ambiguousCol(asWritten)
		}
	case 2:
		t, c := parts[0].Value, parts[1].Value
		for _, s := range sc.sources {
			if s.name == t {
				for _, col := range s.cols {
					if col.name == c {
						return col, nil
					}
				}
				return outCol{}, notFoundCol(asWritten)
			}
		}
	case 3:
		s, t, c := parts[0].Value, parts[1].Value, parts[2].Value
		for _, src := range sc.sources {
			if src.table != nil && src.table.Schema == s && src.table.Name == t {
				for _, col := range src.cols {
					if col.name == c {
						return col, nil
					}
				}
				return outCol{}, notFoundCol(asWritten)
			}
		}
	case 4:
		c2, s, t, c := parts[0].Value, parts[1].Value, parts[2].Value, parts[3].Value
		for _, src := range sc.sources {
			if src.table != nil && src.table.Catalog == c2 && src.table.Schema == s && src.table.Name == t {
				for _, col := range src.cols {
					if col.name == c {
						return col, nil
					}
				}
				return outCol{}, notFoundCol(asWritten)
			}
		}
	default:
		return outCol{}, notFoundCol(asWritten)
	}
	if sc.parent != nil {
		return sc.parent.resolve(parts)
	}
	return outCol{}, notFoundCol(asWritten)
}

func ambiguousCol(name string) error {
	return maskerr.Errorf(maskerr.ValidationError,
		"validation failed: column '%s' is ambiguous", name)
}

func notFoundCol(name string) error {
	return maskerr.Errorf(maskerr.ValidationError,
		"validation failed: column '%s' not found", name)
}

// ---------------- 表达式 origins ----------------

// exprOrigins 计算表达式来源并集；hasSub 表示表达式含标量子查询/EXISTS/
// IN 子查询（两阶段处理）。同时充当名字解析边界：所有标识符必须可解析。
func exprOrigins(e ast.Expr, sc *scope) ([]Origin, bool, error) {
	if e == nil {
		return nil, false, nil
	}
	switch n := e.(type) {
	case *ast.Ident:
		col, err := sc.resolve(n.Parts)
		if err != nil {
			return nil, false, err
		}
		return col.origins, false, nil
	case *ast.Literal, *ast.Param, *ast.Star:
		return nil, false, nil
	case *ast.Unary:
		return exprOrigins(n.X, sc)
	case *ast.Binary:
		l, hasL, err := exprOrigins(n.L, sc)
		if err != nil {
			return nil, false, err
		}
		r, hasR, err := exprOrigins(n.R, sc)
		if err != nil {
			return nil, false, err
		}
		return append(l, r...), hasL || hasR, nil
	case *ast.IsPred:
		o, has, err := exprOrigins(n.X, sc)
		if err != nil {
			return nil, false, err
		}
		if n.R != nil {
			r, hasR, err := exprOrigins(n.R, sc)
			if err != nil {
				return nil, false, err
			}
			o = append(o, r...)
			has = has || hasR
		}
		return o, has, nil
	case *ast.Between:
		o, has, err := exprOrigins(n.X, sc)
		if err != nil {
			return nil, false, err
		}
		for _, part := range []ast.Expr{n.Low, n.High} {
			po, ph, err := exprOrigins(part, sc)
			if err != nil {
				return nil, false, err
			}
			o = append(o, po...)
			has = has || ph
		}
		return o, has, nil
	case *ast.InPred:
		o, has, err := exprOrigins(n.X, sc)
		if err != nil {
			return nil, false, err
		}
		for _, item := range n.List {
			io, ih, err := exprOrigins(item, sc)
			if err != nil {
				return nil, false, err
			}
			o = append(o, io...)
			has = has || ih
		}
		if n.Sub != nil {
			has = true
		}
		return o, has, nil
	case *ast.Like:
		o, has, err := exprOrigins(n.X, sc)
		if err != nil {
			return nil, false, err
		}
		for _, part := range []ast.Expr{n.Pattern, n.Esc} {
			if part == nil {
				continue
			}
			po, ph, err := exprOrigins(part, sc)
			if err != nil {
				return nil, false, err
			}
			o = append(o, po...)
			has = has || ph
		}
		return o, has, nil
	case *ast.Cast:
		return exprOrigins(n.X, sc)
	case *ast.Collate:
		return exprOrigins(n.X, sc)
	case *ast.Case:
		var origins []Origin
		has := false
		if n.Operand != nil {
			o, h, err := exprOrigins(n.Operand, sc)
			if err != nil {
				return nil, false, err
			}
			origins = append(origins, o...)
			has = has || h
		}
		for _, w := range n.Whens {
			o, h, err := exprOrigins(w.Cond, sc)
			if err != nil {
				return nil, false, err
			}
			origins = append(origins, o...)
			has = has || h
			o, h, err = exprOrigins(w.Then, sc)
			if err != nil {
				return nil, false, err
			}
			origins = append(origins, o...)
			has = has || h
		}
		if n.Else != nil {
			o, h, err := exprOrigins(n.Else, sc)
			if err != nil {
				return nil, false, err
			}
			origins = append(origins, o...)
			has = has || h
		}
		return origins, has, nil
	case *ast.Call:
		var origins []Origin
		has := false
		if !n.Star {
			for _, arg := range n.Args {
				o, h, err := exprOrigins(arg, sc)
				if err != nil {
					return nil, false, err
				}
				origins = append(origins, o...)
				has = has || h
			}
		}
		if n.Over != nil {
			// 窗口：分区/排序/帧偏移只做名字解析，不贡献 origins
			for _, p := range n.Over.PartitionBy {
				if _, _, err := exprOrigins(p, sc); err != nil {
					return nil, false, err
				}
			}
			for _, it := range n.Over.Order {
				if _, _, err := exprOrigins(it.Expr, sc); err != nil {
					return nil, false, err
				}
			}
			for _, b := range []ast.FrameBound{frameStart(n.Over.Frame), frameEnd(n.Over.Frame)} {
				if b.Offset != nil {
					if _, _, err := exprOrigins(b.Offset, sc); err != nil {
						return nil, false, err
					}
				}
			}
		}
		return origins, has, nil
	case *ast.Subquery, *ast.Exists:
		return nil, true, nil
	default:
		return nil, false, nil
	}
}

// checkSubqueriesSafe 对齐 LineageAnalyzer.subquerySafe：每个投影子查询
// 必须恰一列，且该列无来源（或不命中任何策略）；更深子查询先递归同规则。
// 任一不满足 → 整语句 fail-closed（LINEAGE_UNKNOWN）。
func (a *Analyzer) checkSubqueriesSafe(e ast.Expr, sc *scope) error {
	for _, sub := range collectSubqueries(e) {
		var q ast.Query
		switch s := sub.(type) {
		case *ast.Subquery:
			q = s.Query
		case *ast.Exists:
			q = s.Query
		case *ast.InPred:
			q = s.Sub.Query
		}
		cols, _, err := a.queryOutputsInner(q, sc)
		if err != nil {
			return err // 内层 fail-closed / 校验失败 → 外层同样失败
		}
		if len(cols) != 1 {
			return maskerr.New(maskerr.LineageUnknown,
				"cannot safely trace lineage through a subquery in the projection; cannot rewrite this statement")
		}
		col := cols[0]
		if col.unknown {
			return maskerr.New(maskerr.LineageUnknown,
				"cannot safely trace lineage through a subquery in the projection; cannot rewrite this statement")
		}
		if len(col.origins) > 0 {
			keys := make([]metadata.ColumnKey, 0, len(col.origins))
			for _, o := range col.origins {
				keys = append(keys, o.Key)
			}
			if _, hit := policy.SelectMask(keys, a.engine); hit {
				return maskerr.New(maskerr.LineageUnknown,
					"cannot safely trace lineage through a subquery in the projection; cannot rewrite this statement")
			}
		}
	}
	return nil
}

// collectSubqueries 收集表达式中的子查询节点（不深入子查询内部——内层由
// queryOutputs 的递归覆盖）。
func collectSubqueries(e ast.Expr) []ast.Expr {
	var out []ast.Expr
	var walk func(x ast.Expr)
	walk = func(x ast.Expr) {
		if x == nil {
			return
		}
		switch n := x.(type) {
		case *ast.Subquery, *ast.Exists:
			out = append(out, x)
		case *ast.InPred:
			if n.Sub != nil {
				out = append(out, x)
			}
			walk(n.X)
			for _, item := range n.List {
				walk(item)
			}
		case *ast.Unary:
			walk(n.X)
		case *ast.Binary:
			walk(n.L)
			walk(n.R)
		case *ast.IsPred:
			walk(n.X)
			walk(n.R)
		case *ast.Between:
			walk(n.X)
			walk(n.Low)
			walk(n.High)
		case *ast.Like:
			walk(n.X)
			walk(n.Pattern)
			walk(n.Esc)
		case *ast.Cast:
			walk(n.X)
		case *ast.Collate:
			walk(n.X)
		case *ast.Case:
			walk(n.Operand)
			for _, w := range n.Whens {
				walk(w.Cond)
				walk(w.Then)
			}
			walk(n.Else)
		case *ast.Call:
			for _, arg := range n.Args {
				walk(arg)
			}
		}
	}
	walk(e)
	return out
}

// ---------------- ORDER BY 宽松解析 ----------------

// resolveOrderLenient 解析 ORDER BY 项：序号字面量直接边界检查；表达式在
// "输出列伪作用域"（输出别名优先，父作用域兜底）中解析——对齐 Calcite
// 允许 ORDER BY 表达式引用输出别名（如 CASE WHEN alias = 0 THEN …）。
func (a *Analyzer) resolveOrderLenient(items []ast.OrderItem, cols []outCol, sc *scope) error {
	pscope := &scope{parent: sc, sources: []*source{{name: "", cols: cols}}}
	for _, it := range items {
		if lit, ok := it.Expr.(*ast.Literal); ok && lit.Kind == ast.LitInt {
			n := 0
			for _, ch := range lit.Text {
				if ch < '0' || ch > '9' {
					n = -1
					break
				}
				n = n*10 + int(ch-'0')
			}
			if n >= 1 && n <= len(cols) {
				continue
			}
			return maskerr.Errorf(maskerr.ValidationError,
				"validation failed: ORDER BY ordinal %s is out of range", lit.Text)
		}
		if _, _, err := exprOrigins(it.Expr, pscope); err != nil {
			return err
		}
	}
	return nil
}

// resolveGroupItem GROUP BY 项解析（序号字面量放行）。
func resolveGroupItem(g ast.GroupItem, sc *scope) error {
	switch g.Op {
	case ast.GroupSimple:
		if lit, ok := g.Expr.(*ast.Literal); ok && lit.Kind == ast.LitInt {
			return nil
		}
		if _, _, err := exprOrigins(g.Expr, sc); err != nil {
			return err
		}
	case ast.GroupRollup, ast.GroupCube:
		for _, e := range g.Exprs {
			if _, _, err := exprOrigins(e, sc); err != nil {
				return err
			}
		}
	default:
		for _, set := range g.Sets {
			for _, e := range set {
				if _, _, err := exprOrigins(e, sc); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

// ---------------- Frame 辅助 ----------------

func frameStart(f *ast.Frame) ast.FrameBound {
	if f == nil {
		return ast.FrameBound{}
	}
	return f.Start
}

func frameEnd(f *ast.Frame) ast.FrameBound {
	if f == nil || !f.HasEnd {
		return ast.FrameBound{}
	}
	return f.End
}
