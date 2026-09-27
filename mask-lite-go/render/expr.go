package render

import (
	"fmt"
	"regexp"
	"strings"

	"io.masklite/go/ast"
)

// Expr 渲染表达式（括号按优先级最小化）。
func Expr(e ast.Expr) string { return exprPrec(e, 0) }

// 表达式优先级（镜像解析层级；越大越紧）。
const (
	precOr = iota + 1
	precAnd
	precNot
	precPred
	precAdd
	precMul
	precUnary
	precPrimary
)

func precedence(e ast.Expr) int {
	switch n := e.(type) {
	case *ast.Binary:
		switch n.Op {
		case ast.OpOr:
			return precOr
		case ast.OpAnd:
			return precAnd
		case ast.OpAdd, ast.OpSub, ast.OpConcat:
			return precAdd
		case ast.OpMul, ast.OpDiv, ast.OpMod:
			return precMul
		default:
			return precPred
		}
	case *ast.Unary:
		if n.Op == ast.UnaryNot {
			return precNot
		}
		return precUnary
	case *ast.IsPred, *ast.Between, *ast.InPred, *ast.Like:
		return precPred
	default:
		return precPrimary
	}
}

// wrap 子节点渲染：优先级更低时加括号；同级且在右侧时也加括号
// （保持左结合结构）。
func wrap(e ast.Expr, parentPrec int, right bool) string {
	p := precedence(e)
	if p < parentPrec || (p == parentPrec && right) {
		return "(" + exprPrec(e, 0) + ")"
	}
	return exprPrec(e, parentPrec)
}

func exprPrec(e ast.Expr, _ int) string {
	switch n := e.(type) {
	case *ast.Ident:
		return identPartsStr(n.Parts)
	case *ast.Star:
		if len(n.Prefix) > 0 {
			return identPartsStr(n.Prefix) + ".*"
		}
		return "*"
	case *ast.Literal:
		return literalStr(n)
	case *ast.Param:
		if n.Index >= 0 {
			return fmt.Sprintf("?%d", n.Index)
		}
		return "?"
	case *ast.Unary:
		switch n.Op {
		case ast.UnaryNot:
			x := exprPrec(n.X, 0)
			if precedence(n.X) <= precPred {
				x = "(" + x + ")"
			}
			return "NOT " + x
		case ast.UnaryNeg:
			return "-" + wrap(n.X, precUnary, false)
		default:
			return "+" + wrap(n.X, precUnary, false)
		}
	case *ast.Binary:
		op := ""
		switch n.Op {
		case ast.OpOr:
			op = "OR"
		case ast.OpAnd:
			op = "AND"
		case ast.OpEq:
			op = "="
		case ast.OpNe:
			op = "<>"
		case ast.OpLt:
			op = "<"
		case ast.OpLe:
			op = "<="
		case ast.OpGt:
			op = ">"
		case ast.OpGe:
			op = ">="
		case ast.OpAdd:
			op = "+"
		case ast.OpSub:
			op = "-"
		case ast.OpConcat:
			op = "||"
		case ast.OpMul:
			op = "*"
		case ast.OpDiv:
			op = "/"
		case ast.OpMod:
			op = "%"
		}
		pp := precedence(n)
		return wrap(n.L, pp, false) + " " + op + " " + wrap(n.R, pp, true)
	case *ast.IsPred:
		s := wrap(n.X, precPred, false) + " IS "
		if n.Neg {
			s += "NOT "
		}
		switch n.What {
		case ast.IsNull:
			return s + "NULL"
		case ast.IsTrue:
			return s + "TRUE"
		case ast.IsFalse:
			return s + "FALSE"
		case ast.IsUnknown:
			return s + "UNKNOWN"
		default:
			return s + "DISTINCT FROM " + wrap(n.R, precPred, false)
		}
	case *ast.Between:
		s := wrap(n.X, precPred, false) + " "
		if n.Neg {
			s += "NOT "
		}
		s += "BETWEEN "
		if n.Symmetric {
			s += "SYMMETRIC "
		} else {
			s += "ASYMMETRIC "
		}
		return s + wrap(n.Low, precAdd, false) + " AND " + wrap(n.High, precAdd, false)
	case *ast.InPred:
		s := wrap(n.X, precPred, false) + " "
		if n.Neg {
			s += "NOT "
		}
		s += "IN ("
		if n.Sub != nil {
			s += Query(n.Sub.Query)
		} else {
			s += exprListStr(n.List)
		}
		return s + ")"
	case *ast.Like:
		s := wrap(n.X, precPred, false) + " "
		if n.Neg {
			s += "NOT "
		}
		switch n.Kind {
		case ast.LikeI:
			s += "ILIKE "
		case ast.LikeSimilar:
			s += "SIMILAR TO "
		default:
			s += "LIKE "
		}
		s += wrap(n.Pattern, precAdd, false)
		if n.Esc != nil {
			s += " ESCAPE " + wrap(n.Esc, precAdd, false)
		}
		return s
	case *ast.Exists:
		return "EXISTS (" + Query(n.Query) + ")"
	case *ast.Subquery:
		return "(" + Query(n.Query) + ")"
	case *ast.Call:
		return callStr(n)
	case *ast.Case:
		return caseStr(n)
	case *ast.Cast:
		return "CAST(" + exprPrec(n.X, 0) + " AS " + typeStr(n.Type) + ")"
	case *ast.Collate:
		return wrap(n.X, precUnary, false) + " COLLATE " + Ident(n.Collation.Value)
	default:
		return ""
	}
}

func literalStr(n *ast.Literal) string {
	switch n.Kind {
	case ast.LitNull:
		return "NULL"
	case ast.LitTrue:
		return "TRUE"
	case ast.LitFalse:
		return "FALSE"
	case ast.LitString:
		return quoteString(n.Text)
	case ast.LitDate:
		return "DATE " + quoteString(n.Text)
	case ast.LitTime:
		return "TIME " + quoteString(n.Text)
	case ast.LitTimestamp:
		return "TIMESTAMP " + quoteString(n.Text)
	case ast.LitInterval:
		s := "INTERVAL " + quoteString(n.Text) + " " + n.Unit
		if n.UnitTo != "" {
			s += " TO " + n.UnitTo
		}
		return s
	default:
		return n.Text
	}
}

// QuoteString 渲染单引号字符串字面量（' 双写转义）；包装器参数渲染复用。
func QuoteString(v string) string {
	return "'" + strings.ReplaceAll(v, "'", "''") + "'"
}

// quoteString 包内简称。
func quoteString(v string) string { return QuoteString(v) }

// canonicalNames 渲染为规范大写名的函数/算子（对齐 Calcite 标准算子表）；
// 未列出的函数按解析后的拼写原样渲染（未引号已小写）。
var canonicalNames = map[string]string{
	"count": "COUNT", "sum": "SUM", "avg": "AVG", "min": "MIN", "max": "MAX",
	"coalesce": "COALESCE", "nullif": "NULLIF", "substring": "SUBSTRING",
	"upper": "UPPER", "lower": "LOWER", "trim": "TRIM", "position": "POSITION",
	"exists": "EXISTS", "abs": "ABS", "mod": "MOD", "power": "POWER",
	"floor": "FLOOR", "ceil": "CEIL", "ceiling": "CEILING", "ln": "LN",
	"exp": "EXP", "sqrt": "SQRT", "char_length": "CHAR_LENGTH",
	"character_length": "CHARACTER_LENGTH", "octet_length": "OCTET_LENGTH",
	"cardinality": "CARDINALITY", "convert": "CONVERT", "overlay": "OVERLAY",
}

func callStr(n *ast.Call) string {
	name := n.Name.Value
	if canon, ok := canonicalNames[strings.ToLower(name)]; ok {
		name = canon
	} else {
		name = Ident(name)
	}
	s := name + "("
	if n.Star {
		s += "*"
	} else {
		if n.Distinct {
			s += "DISTINCT "
		}
		s += exprListStr(n.Args)
	}
	s += ")"
	if n.Over != nil {
		s += " OVER (" + windowStr(n.Over) + ")"
	}
	return s
}

func windowStr(w *ast.Window) string {
	var parts []string
	if len(w.PartitionBy) > 0 {
		parts = append(parts, "PARTITION BY "+exprListStr(w.PartitionBy))
	}
	if len(w.Order) > 0 {
		items := make([]string, 0, len(w.Order))
		for _, it := range w.Order {
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
		parts = append(parts, "ORDER BY "+strings.Join(items, ", "))
	}
	if w.Frame != nil {
		unit := "ROWS"
		if w.Frame.Unit == ast.FrameRange {
			unit = "RANGE"
		}
		s := unit + " "
		if w.Frame.HasEnd {
			s += "BETWEEN " + boundStr(w.Frame.Start) + " AND " + boundStr(w.Frame.End)
		} else {
			s += boundStr(w.Frame.Start)
		}
		parts = append(parts, s)
	}
	return strings.Join(parts, " ")
}

func boundStr(b ast.FrameBound) string {
	switch b.Kind {
	case ast.BoundUnboundedPreceding:
		return "UNBOUNDED PRECEDING"
	case ast.BoundPreceding:
		return Expr(b.Offset) + " PRECEDING"
	case ast.BoundCurrentRow:
		return "CURRENT ROW"
	case ast.BoundFollowing:
		return Expr(b.Offset) + " FOLLOWING"
	default:
		return "UNBOUNDED FOLLOWING"
	}
}

func caseStr(n *ast.Case) string {
	var b strings.Builder
	b.WriteString("CASE")
	if n.Operand != nil {
		b.WriteString(" " + Expr(n.Operand))
	}
	for _, w := range n.Whens {
		b.WriteString(" WHEN " + Expr(w.Cond) + " THEN " + Expr(w.Then))
	}
	if n.Else != nil {
		b.WriteString(" ELSE " + Expr(n.Else))
	}
	b.WriteString(" END")
	return b.String()
}

// typeStr 渲染类型（对齐 Calcite：DECIMAL(10, 2)、VARCHAR(20)、DOUBLE）。
func typeStr(t ast.TypeSpec) string {
	s := t.Name
	if t.Precision != nil {
		s += "(" + fmt.Sprintf("%d", *t.Precision)
		if t.Scale != nil {
			s += ", " + fmt.Sprintf("%d", *t.Scale)
		}
		s += ")"
	}
	if t.Suffix != "" {
		s += " " + t.Suffix
	}
	return s
}

// ---------------- 标识符引号策略 ----------------

// PG 完全保留字（PostgresqlIdentifierPolicy 同表）：命中或不符合裸标识符
// 形态时双引号包裹。
var pgReserved = map[string]bool{
	"ALL": true, "ANALYSE": true, "ANALYZE": true, "AND": true, "ANY": true,
	"ARRAY": true, "AS": true, "ASC": true, "ASYMMETRIC": true, "BOTH": true,
	"CASE": true, "CAST": true, "CHECK": true, "COLLATE": true, "COLUMN": true,
	"CONSTRAINT": true, "CREATE": true, "CURRENT_CATALOG": true,
	"CURRENT_DATE": true, "CURRENT_ROLE": true, "CURRENT_TIME": true,
	"CURRENT_TIMESTAMP": true, "CURRENT_USER": true, "DEFAULT": true,
	"DEFERRABLE": true, "DESC": true, "DISTINCT": true, "DO": true,
	"ELSE": true, "END": true, "EXCEPT": true, "FALSE": true, "FETCH": true,
	"FOR": true, "FOREIGN": true, "FROM": true, "GRANT": true, "GROUP": true,
	"HAVING": true, "IN": true, "INITIALLY": true, "INTERSECT": true,
	"INTO": true, "LATERAL": true, "LEADING": true, "LIMIT": true,
	"LOCALTIME": true, "LOCALTIMESTAMP": true, "NOT": true, "NULL": true,
	"OFFSET": true, "ON": true, "ONLY": true, "OR": true, "ORDER": true,
	"PLACING": true, "PRIMARY": true, "REFERENCES": true, "RETURNING": true,
	"SELECT": true, "SESSION_USER": true, "SOME": true, "SYMMETRIC": true,
	"TABLE": true, "THEN": true, "TO": true, "TRAILING": true, "TRUE": true,
	"UNION": true, "UNIQUE": true, "USER": true, "USING": true,
	"VARIADIC": true, "WHEN": true, "WHERE": true, "WINDOW": true, "WITH": true,
}

var plainIdentifier = regexp.MustCompile(`^[a-z_][a-z0-9_$]*$`)

// Ident 按标识符策略渲染：裸小写标识符直接输出，其余双引号（内部 "
// 双写）。生成的外层投影与别名清单都用它。
func Ident(name string) string {
	if plainIdentifier.MatchString(name) && !pgReserved[strings.ToUpper(name)] {
		return name
	}
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}
