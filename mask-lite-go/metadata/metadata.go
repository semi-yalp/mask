// Package metadata 定义表/列元数据模型、规范化列键与 PostgreSQL 类型声明
// 词表（对齐 Java PostgresqlTypeResolver，错误消息逐字一致）。
package metadata

import (
	"regexp"
	"strconv"
	"strings"

	"io.masklite/go/maskerr"
)

// Column 是表的一列声明。
type Column struct {
	Name        string // YAML 原拼写
	TypeName    string // 规范类型名（BOOLEAN/INTEGER/DECIMAL/...）
	Precision   *int
	Scale       *int
	Declaration string // 原始声明文本（trim 后）
}

// Table 是一张声明表。
type Table struct {
	Catalog   string
	Schema    string
	Name      string
	Columns   []Column
	RowFilter string // 空 = 未配置
}

// QualifiedName 返回 catalog.schema.name（原拼写，错误消息用）。
func (t *Table) QualifiedName() string {
	return t.Catalog + "." + t.Schema + "." + t.Name
}

// ColumnKey 是规范化（trim+lowercase）的四段列键。
type ColumnKey struct {
	Catalog string
	Schema  string
	Table   string
	Column  string
}

// Normalize 规范化身份名（trim + lowercase，对齐 ColumnKey.normalize）。
func Normalize(s string) string {
	return strings.ToLower(strings.TrimSpace(s))
}

// Key 构造规范化列键。
func Key(catalog, schema, table, column string) ColumnKey {
	return ColumnKey{
		Catalog: Normalize(catalog),
		Schema:  Normalize(schema),
		Table:   Normalize(table),
		Column:  Normalize(column),
	}
}

// TableKey 构造规范化表键字符串 c.s.t。
func TableKey(catalog, schema, table string) string {
	return Normalize(catalog) + "." + Normalize(schema) + "." + Normalize(table)
}

// String 输出 c.s.t.col（规范化后）。
func (k ColumnKey) String() string {
	return k.Catalog + "." + k.Schema + "." + k.Table + "." + k.Column
}

// CompareKeys 字典序比较（catalog→schema→table→column），对齐
// ColumnKey.ORDER（多来源 tie-break 与歧义候选排序用）。
func CompareKeys(a, b ColumnKey) int {
	if c := strings.Compare(a.Catalog, b.Catalog); c != 0 {
		return c
	}
	if c := strings.Compare(a.Schema, b.Schema); c != 0 {
		return c
	}
	if c := strings.Compare(a.Table, b.Table); c != 0 {
		return c
	}
	return strings.Compare(a.Column, b.Column)
}

// ParseColumn 解析 PostgreSQL 标量类型声明（词表与错误消息对齐
// PostgresqlTypeResolver；NUMERIC(p) ≡ (p,0)；char 缺省 n=1）。
func ParseColumn(name, typeDeclaration string) (Column, error) {
	raw := strings.TrimSpace(typeDeclaration)
	lowered := strings.ToLower(raw)
	base, precision, scale, ok := splitTypeParams(lowered)
	if !ok {
		return Column{}, typeError(raw)
	}
	switch base {
	case "boolean", "bool":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "boolean"
	case "smallint", "int2":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "smallint"
	case "integer", "int", "int4":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "integer"
	case "bigint", "int8":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "bigint"
	case "real", "float4":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "real"
	case "double precision", "double", "float8", "float":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "double precision"
	case "decimal", "numeric":
		// NUMERIC(p) == NUMERIC(p, 0)
		if precision != nil && scale == nil {
			zero := 0
			scale = &zero
		}
		base = "decimal"
	case "char", "character":
		if precision == nil {
			one := 1
			precision = &one
		}
		base = "char"
	case "varchar", "character varying":
		base = "varchar"
	case "text":
		base = "text"
	case "date":
		if err := requireNoParams(lowered); err != nil {
			return Column{}, err
		}
		base = "date"
	case "timestamp":
		base = "timestamp"
	case "timestamptz", "timestamp with time zone":
		base = "timestamptz"
	case "time":
		base = "time"
	case "timetz", "time with time zone":
		base = "timetz"
	default:
		return Column{}, typeError(raw)
	}
	col := Column{Name: name, Declaration: raw, Precision: precision, Scale: scale}
	switch base {
	case "boolean":
		col.TypeName = "BOOLEAN"
	case "smallint":
		col.TypeName = "SMALLINT"
	case "integer":
		col.TypeName = "INTEGER"
	case "bigint":
		col.TypeName = "BIGINT"
	case "real":
		col.TypeName = "REAL"
	case "double precision":
		col.TypeName = "DOUBLE"
	case "decimal":
		col.TypeName = "DECIMAL"
	case "char":
		col.TypeName = "CHAR"
	case "varchar", "text":
		col.TypeName = "VARCHAR"
	case "date":
		col.TypeName = "DATE"
	case "timestamp":
		col.TypeName = "TIMESTAMP"
	case "timestamptz":
		col.TypeName = "TIMESTAMP_WITH_LOCAL_TIME_ZONE"
	case "time":
		col.TypeName = "TIME"
	case "timetz":
		col.TypeName = "TIME_WITH_LOCAL_TIME_ZONE"
	}
	return col, nil
}

func requireNoParams(lowered string) error {
	if strings.Contains(lowered, "(") {
		return typeError(lowered)
	}
	return nil
}

// typeError 对齐 PostgresqlTypeResolver.parseError 的消息。
func typeError(raw string) error {
	return maskerr.Errorf(maskerr.ConfigError,
		"unsupported or malformed type declaration '%s'; supported scalar types: "+
			"boolean, smallint, integer, bigint, real, double precision, decimal(p,s)/numeric(p,s), "+
			"char(n), varchar(n), text, date, timestamp[(p)], timestamp with time zone, time[(p)]", raw)
}

var typeParamsRe = regexp.MustCompile(`^(.+?)\s*\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)$`)

// splitTypeParams 对齐 TypeResolver.split(lowered, null)：剥掉尾部 (p[,s])
// 参数；没有参数时整个串是 base。
func splitTypeParams(lowered string) (base string, precision, scale *int, ok bool) {
	m := typeParamsRe.FindStringSubmatch(lowered)
	if m == nil {
		return lowered, nil, nil, true
	}
	p, err := strconv.Atoi(m[2])
	if err != nil {
		return "", nil, nil, false
	}
	precision = &p
	if m[3] != "" {
		s, err := strconv.Atoi(m[3])
		if err != nil {
			return "", nil, nil, false
		}
		scale = &s
	}
	return strings.TrimSpace(m[1]), precision, scale, true
}
