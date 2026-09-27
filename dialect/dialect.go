// Package dialect 定义五个查询引擎(PostgreSQL/Trino/MySQL/Hive/SparkSQL)
// 的方言 Profile 注册表。字段值与 Java 版 io.sqlmask.dialect 包
// (五个 *DialectAdapter、DialectProfiles、DialectCapabilities)逐字段对齐;
// 后续 lexer/parser/contract 以此为方言差异的唯一来源。
package dialect

import (
	"strings"

	"io.sqlmask/go/maskerr"
)

// Quoting 标识符引用方式(对应 Calcite Quoting 枚举)。
type Quoting int

const (
	// DoubleQuote 双引号引用(PostgreSQL/Trino)。
	DoubleQuote Quoting = iota
	// BackTick 反引号引用(MySQL/Hive/SparkSQL)。
	BackTick
)

// Casing 标识符大小写折叠方式(对应 Calcite Casing 枚举)。
type Casing int

const (
	// ToLower 折叠为小写(pg/trino/hive/sparksql 的未引用标识符)。
	ToLower Casing = iota
	// Unchanged 保持原样。
	Unchanged
)

// SchemaPathStyle 非限定表引用的搜索路径风格
// (对应 DialectProfile.SchemaPathStyle)。
type SchemaPathStyle int

const (
	// CatalogSchema 产生 [catalog, schema] 二元路径(PostgreSQL/Trino)。
	CatalogSchema SchemaPathStyle = iota
	// CatalogSchemaAndSchema 额外产生一元 [catalog] 路径,
	// 使 MySQL 两段名 db.table 可解析(MySQL/Hive/SparkSQL)。
	CatalogSchemaAndSchema
)

// Profile 一个查询引擎方言的声明式描述;全部引擎差异集中于此,
// 重写管线保持方言无关。
type Profile struct {
	Name                         string
	Quoting                      Quoting
	UnquotedCasing, QuotedCasing Casing
	CaseSensitive                bool
	AllowTopN                    bool // 五方言均 false(SqlMaskConformance.of 委托, false, ...)
	AllowInsertOverwrite         bool // hive/sparksql true(SqlMaskConformance.of ..., true)
	SchemaPathStyle              SchemaPathStyle
	// CanWrapDuplicateOutputNames 与 SupportsDerivedColumnAliasList
	// 对应 Java DialectCapabilities 两位:pg/trino 为 STRICT(false, true),
	// mysql/hive/sparksql 为 STRICT_NO_ALIAS_LIST(false, false)。
	CanWrapDuplicateOutputNames    bool // 五方言均 false
	SupportsDerivedColumnAliasList bool // pg/trino true;mysql/hive/sparksql false
}

// All 返回全部方言 Profile,顺序为注册顺序:
// postgresql, trino, mysql, hive, sparksql。
func All() []*Profile {
	out := make([]*Profile, len(registry))
	copy(out, registry)
	return out
}

// ByName 按名称查找方言 Profile(大小写不敏感,
// 对齐 DialectProfiles.byName);未知名返回 maskerr.CONFIG_ERROR,
// message 与 Java 版逐字一致,方言清单按注册顺序。
func ByName(name string) (*Profile, error) {
	key := strings.ToLower(name)
	for _, p := range registry {
		if p.Name == key {
			return p, nil
		}
	}
	return nil, maskerr.Errorf(maskerr.ConfigError,
		"unsupported dialect '%s'; supported dialects: %s", name, strings.Join(supportedNames, ", "))
}
