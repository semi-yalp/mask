package dialect

import (
	"errors"
	"testing"

	"io.sqlmask/go/maskerr"
)

func TestAllOrder(t *testing.T) {
	// 注册顺序(即 ByName 错误 message 中支持方言清单的顺序)。
	want := []string{"postgresql", "trino", "mysql", "hive", "sparksql"}
	got := All()
	if len(got) != len(want) {
		t.Fatalf("All() len = %d, want %d", len(got), len(want))
	}
	for i, p := range got {
		if p.Name != want[i] {
			t.Fatalf("All()[%d].Name = %q, want %q", i, p.Name, want[i])
		}
	}
}

// TestProfiles 表驱动断言五个方言的每个字段值,
// 逐字对齐 Java 源(PostgresqlDialectAdapter 等)。
func TestProfiles(t *testing.T) {
	tests := []struct {
		name string
		want Profile
	}{
		{"postgresql", Profile{
			Name:                           "postgresql",
			Quoting:                        DoubleQuote,
			UnquotedCasing:                 ToLower,
			QuotedCasing:                   Unchanged,
			CaseSensitive:                  true,
			AllowTopN:                      false,
			AllowInsertOverwrite:           false,
			SchemaPathStyle:                CatalogSchema,
			CanWrapDuplicateOutputNames:    false,
			SupportsDerivedColumnAliasList: true,
		}},
		{"trino", Profile{
			Name:                           "trino",
			Quoting:                        DoubleQuote,
			UnquotedCasing:                 ToLower,
			QuotedCasing:                   Unchanged,
			CaseSensitive:                  true,
			AllowTopN:                      false,
			AllowInsertOverwrite:           false,
			SchemaPathStyle:                CatalogSchema,
			CanWrapDuplicateOutputNames:    false,
			SupportsDerivedColumnAliasList: true,
		}},
		{"mysql", Profile{
			Name:                           "mysql",
			Quoting:                        BackTick,
			UnquotedCasing:                 Unchanged,
			QuotedCasing:                   Unchanged,
			CaseSensitive:                  false,
			AllowTopN:                      false,
			AllowInsertOverwrite:           false,
			SchemaPathStyle:                CatalogSchemaAndSchema,
			CanWrapDuplicateOutputNames:    false,
			SupportsDerivedColumnAliasList: false,
		}},
		{"hive", Profile{
			Name:                           "hive",
			Quoting:                        BackTick,
			UnquotedCasing:                 ToLower,
			QuotedCasing:                   Unchanged,
			CaseSensitive:                  false,
			AllowTopN:                      false,
			AllowInsertOverwrite:           true,
			SchemaPathStyle:                CatalogSchemaAndSchema,
			CanWrapDuplicateOutputNames:    false,
			SupportsDerivedColumnAliasList: false,
		}},
		{"sparksql", Profile{
			Name:                           "sparksql",
			Quoting:                        BackTick,
			UnquotedCasing:                 ToLower,
			QuotedCasing:                   Unchanged,
			CaseSensitive:                  false,
			AllowTopN:                      false,
			AllowInsertOverwrite:           true,
			SchemaPathStyle:                CatalogSchemaAndSchema,
			CanWrapDuplicateOutputNames:    false,
			SupportsDerivedColumnAliasList: false,
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p, err := ByName(tt.name)
			if err != nil {
				t.Fatalf("ByName(%q) error: %v", tt.name, err)
			}
			// 结构体整体相等 == 逐字段相等;字段清单由编译期字面量保证完整。
			if *p != tt.want {
				t.Fatalf("ByName(%q) = %+v, want %+v", tt.name, *p, tt.want)
			}
		})
	}
}

func TestByNameCaseInsensitive(t *testing.T) {
	p, err := ByName("PostgreSQL")
	if err != nil {
		t.Fatalf("ByName(\"PostgreSQL\") error: %v", err)
	}
	if p.Name != "postgresql" {
		t.Fatalf("ByName(\"PostgreSQL\").Name = %q, want %q", p.Name, "postgresql")
	}
}

func TestByNameUnknown(t *testing.T) {
	p, err := ByName("spark")
	if p != nil || err == nil {
		t.Fatalf("ByName(\"spark\") = (%v, %v), want (nil, error)", p, err)
	}
	var e *maskerr.Error
	if !errors.As(err, &e) {
		t.Fatalf("ByName(\"spark\") err = %T, want *maskerr.Error", err)
	}
	if e.Code != maskerr.ConfigError {
		t.Fatalf("code = %q, want %q", e.Code, maskerr.ConfigError)
	}
	want := "unsupported dialect 'spark'; supported dialects: postgresql, trino, mysql, hive, sparksql"
	if e.Message != want {
		t.Fatalf("message = %q, want %q", e.Message, want)
	}
}
