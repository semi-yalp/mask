// Package rowfilter 实现行过滤：条件白名单预检（Registry）与派生表注入
// （Rewriter），语义逐条对齐 Java RowFilterRegistry / RowFilterRewriter。
package rowfilter

import (
	"sort"
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/config"
	"io.masklite/go/dialect"
	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
	"io.masklite/go/parser"
)

// Template 是一张受控表的已校验条件模板。
type Template struct {
	Table *metadata.Table
	Cond  ast.Expr
}

// Registry 是受控表条件缓存；IsControlled 是"受控表"唯一判定。
type Registry struct {
	templates map[string]*Template
}

// Build 对每个带 rowFilter 的声明表做三步构建：包裹解析 → 白名单 →
// 条件复核。任何失败都是 CONFIG_ERROR，消息带表前缀（逐字对齐 legacy
// 路径），发生在任何语句处理之前。
func Build(cfg *config.LoadedConfig) (*Registry, error) {
	r := &Registry{templates: map[string]*Template{}}
	for _, t := range cfg.Tables {
		if t.RowFilter == "" {
			continue
		}
		if err := r.register(t); err != nil {
			return nil, err
		}
	}
	return r, nil
}

// IsEmpty 无任何受控表。
func (r *Registry) IsEmpty() bool { return len(r.templates) == 0 }

// IsControlled 受控表判定。
func (r *Registry) IsControlled(catalog, schema, table string) bool {
	_, ok := r.templates[metadata.TableKey(catalog, schema, table)]
	return ok
}

// ConditionTemplateOf 返回条件模板（未命中为 nil）。
func (r *Registry) ConditionTemplateOf(catalog, schema, table string) *Template {
	return r.templates[metadata.TableKey(catalog, schema, table)]
}

func (r *Registry) register(t *metadata.Table) error {
	prefix := "table '" + t.QualifiedName() + "': row filter"
	wrapped := "SELECT * FROM " + t.QualifiedName() + " WHERE " + t.RowFilter
	p, err := parser.New(dialect.PostgreSQL, wrapped)
	if err != nil {
		// 词法错误
		return maskerr.New(maskerr.ConfigError, prefix+" cannot be parsed: "+
			"statement 0: parse error (postgresql): "+err.Error())
	}
	stmt, err := p.ParseStatement()
	if err != nil {
		return maskerr.New(maskerr.ConfigError, prefix+" cannot be parsed: "+
			"statement 0: parse error (postgresql): "+err.Error())
	}
	sel, ok := stmt.(*ast.Select)
	if !ok || len(sel.From) != 1 || sel.Where == nil {
		return maskerr.New(maskerr.ConfigError, prefix+" cannot be parsed: "+
			"statement 0: parse error (postgresql): not a simple condition")
	}
	columns := map[string]bool{}
	for _, c := range t.Columns {
		columns[strings.ToLower(c.Name)] = true
	}
	if err := whitelist(sel.Where, columns); err != nil {
		return maskerr.New(maskerr.ConfigError, prefix+" "+err.Error())
	}
	// 第三步（二次校验）在 Go 实现中与白名单合一：条件标识符必须解析到
	// 声明列，函数/子查询/参数已全部拒绝；类型布尔性不做推断（见
	// docs/differential-notes.md 的已记录偏差）。
	r.templates[metadata.TableKey(t.Catalog, t.Schema, t.Name)] = &Template{Table: t, Cond: sel.Where}
	return nil
}

// whitelist 对齐 RowFilterRegistry.whitelistVisitor：标识符必须单段且为
// 声明列；子查询/动态参数拒绝；算子限于白名单 EnumSet。
func whitelist(e ast.Expr, columns map[string]bool) error {
	switch n := e.(type) {
	case *ast.Ident:
		if len(n.Parts) != 1 {
			return maskerr.New(maskerr.ConfigError,
				"must reference the table's columns directly, without qualification")
		}
		name := n.Parts[0].Value
		if !columns[strings.ToLower(name)] {
			return maskerr.Errorf(maskerr.ConfigError,
				"must only reference declared columns of the filtered table; '%s' is not one", name)
		}
		return nil
	case *ast.Star:
		// Calcite 把 * 解析为多段标识符 → 资格限定拒绝
		return maskerr.New(maskerr.ConfigError,
			"must reference the table's columns directly, without qualification")
	case *ast.Literal, *ast.Param:
		if _, isParam := e.(*ast.Param); isParam {
			return maskerr.New(maskerr.ConfigError, "must not contain dynamic parameters")
		}
		return nil
	case *ast.Binary:
		// 全部二元算子在白名单内
		if err := whitelist(n.L, columns); err != nil {
			return err
		}
		return whitelist(n.R, columns)
	case *ast.Unary:
		return whitelist(n.X, columns)
	case *ast.IsPred:
		switch n.What {
		case ast.IsNull:
			// is_null / is_not_null 允许
		case ast.IsDistinctFrom:
			// is_distinct_from / is_not_distinct_from 允许
		default:
			// is_true / is_false / is_unknown 不在 EnumSet
			name := "is_" + isWhatLower(n.What)
			if n.Neg {
				name = "is_not_" + isWhatLower(n.What)
			}
			return maskerr.Errorf(maskerr.ConfigError,
				"must only use columns, literals and basic comparisons; '%s' is not allowed", name)
		}
		if err := whitelist(n.X, columns); err != nil {
			return err
		}
		if n.R != nil {
			return whitelist(n.R, columns)
		}
		return nil
	case *ast.InPred:
		if n.Sub != nil {
			return maskerr.New(maskerr.ConfigError, "must not contain subqueries")
		}
		if err := whitelist(n.X, columns); err != nil {
			return err
		}
		for _, item := range n.List {
			if err := whitelist(item, columns); err != nil {
				return err
			}
		}
		return nil
	case *ast.Between:
		return notAllowed("between")
	case *ast.Like:
		switch n.Kind {
		case ast.LikeI:
			return notAllowed("ilike")
		case ast.LikeSimilar:
			return notAllowed("similar_to")
		default:
			return notAllowed("like")
		}
	case *ast.Call:
		// 函数（含 CAST/内置/UDF）→ Calcite kind 为 other_function
		return notAllowed("other_function")
	case *ast.Cast:
		return notAllowed("cast")
	case *ast.Case:
		return notAllowed("case")
	case *ast.Collate:
		return notAllowed("collate")
	case *ast.Subquery, *ast.Exists:
		return maskerr.New(maskerr.ConfigError, "must not contain subqueries")
	default:
		return notAllowed("other_function")
	}
}

func isWhatLower(w ast.IsWhat) string {
	switch w {
	case ast.IsTrue:
		return "true"
	case ast.IsFalse:
		return "false"
	case ast.IsUnknown:
		return "unknown"
	default:
		return "null"
	}
}

func notAllowed(kind string) error {
	return maskerr.Errorf(maskerr.ConfigError,
		"must only use columns, literals and basic comparisons; '%s' is not allowed", kind)
}

// sortQualified 排序歧义候选（Java stream().sorted() 字典序）。
func sortQualified(names []string) []string {
	out := append([]string(nil), names...)
	sort.Strings(out)
	return out
}
