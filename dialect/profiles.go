package dialect

// registry 按声明顺序注册五个方言;该顺序同时决定
// ByName 错误 message 中支持方言清单的顺序
// (对齐 DialectProfiles 静态块的 LinkedHashMap 插入序)。
var registry = []*Profile{postgresql, trino, mysql, hive, sparksql}

var supportedNames = profileNames(registry)

func profileNames(ps []*Profile) []string {
	out := make([]string, 0, len(ps))
	for _, p := range ps {
		out = append(out, p.Name)
	}
	return out
}

// postgresql 对齐 PostgresqlDialectAdapter:
// DialectCapabilities.STRICT + SqlMaskConformance.of(DEFAULT, false, false)。
var postgresql = &Profile{
	Name:                           "postgresql",
	Quoting:                        DoubleQuote,
	UnquotedCasing:                 ToLower,
	QuotedCasing:                   Unchanged,
	CaseSensitive:                  true,
	AllowTopN:                      false,
	AllowInsertOverwrite:           false,
	SchemaPathStyle:                CatalogSchema,
	Conformance:                    Default,
	CanWrapDuplicateOutputNames:    false,
	SupportsDerivedColumnAliasList: true,
}

// trino 对齐 TrinoDialectAdapter:字段值与 postgresql 相同
// (DialectCapabilities.STRICT + SqlMaskConformance.of(DEFAULT, false, false))。
var trino = &Profile{
	Name:                           "trino",
	Quoting:                        DoubleQuote,
	UnquotedCasing:                 ToLower,
	QuotedCasing:                   Unchanged,
	CaseSensitive:                  true,
	AllowTopN:                      false,
	AllowInsertOverwrite:           false,
	SchemaPathStyle:                CatalogSchema,
	Conformance:                    Default,
	CanWrapDuplicateOutputNames:    false,
	SupportsDerivedColumnAliasList: true,
}

// mysql 对齐 MysqlDialectAdapter:不折叠大小写、大小写不敏感匹配
// (MySQL 列名大小写不敏感);
// DialectCapabilities.STRICT_NO_ALIAS_LIST + SqlMaskConformance.of(MYSQL_5, false, false)。
var mysql = &Profile{
	Name:                           "mysql",
	Quoting:                        BackTick,
	UnquotedCasing:                 Unchanged,
	QuotedCasing:                   Unchanged,
	CaseSensitive:                  false,
	AllowTopN:                      false,
	AllowInsertOverwrite:           false,
	SchemaPathStyle:                CatalogSchemaAndSchema,
	Conformance:                    MySQL5,
	CanWrapDuplicateOutputNames:    false,
	SupportsDerivedColumnAliasList: false,
}

// hive 对齐 HiveDialectAdapter:未引用标识符折叠为小写(Hive 存储语义),
// INSERT OVERWRITE 扩展开启;
// DialectCapabilities.STRICT_NO_ALIAS_LIST + SqlMaskConformance.of(LENIENT, false, true)。
var hive = &Profile{
	Name:                           "hive",
	Quoting:                        BackTick,
	UnquotedCasing:                 ToLower,
	QuotedCasing:                   Unchanged,
	CaseSensitive:                  false,
	AllowTopN:                      false,
	AllowInsertOverwrite:           true,
	SchemaPathStyle:                CatalogSchemaAndSchema,
	Conformance:                    Lenient,
	CanWrapDuplicateOutputNames:    false,
	SupportsDerivedColumnAliasList: false,
}

// sparksql 对齐 SparkSqlDialectAdapter:未引用标识符折叠为小写(Hive 存储语义),
// INSERT OVERWRITE 扩展开启;
// DialectCapabilities.STRICT_NO_ALIAS_LIST + SqlMaskConformance.of(LENIENT, false, true)。
var sparksql = &Profile{
	Name:                           "sparksql",
	Quoting:                        BackTick,
	UnquotedCasing:                 ToLower,
	QuotedCasing:                   Unchanged,
	CaseSensitive:                  false,
	AllowTopN:                      false,
	AllowInsertOverwrite:           true,
	SchemaPathStyle:                CatalogSchemaAndSchema,
	Conformance:                    Lenient,
	CanWrapDuplicateOutputNames:    false,
	SupportsDerivedColumnAliasList: false,
}
