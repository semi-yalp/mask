package config

import (
	"strings"
	"testing"

	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
)

const validYAML = `
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active'"
      columns:
        - { name: id, type: bigint }
        - { name: phone, type: varchar(20) }
policies:
  mask_phone:
    udf: mask_phone
    arguments: [3, 4]
columns:
  - catalog: crm
    schema: public
    table: customer
    column: phone
    policy: mask_phone
`

func TestLoadValid(t *testing.T) {
	cfg, err := LoadContent(validYAML, "metadata.yaml")
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(cfg.Tables) != 1 || len(cfg.Policies) != 1 || len(cfg.Bindings) != 1 {
		t.Fatalf("shape: %+v", cfg)
	}
	if cfg.Tables[0].RowFilter != "status = 'active'" {
		t.Fatalf("rowFilter: %q", cfg.Tables[0].RowFilter)
	}
	p := cfg.Policies[0]
	if len(p.Arguments) != 2 {
		t.Fatalf("arguments: %#v", p.Arguments)
	}
	if v, ok := p.Arguments[0].(int64); !ok || v != 3 {
		t.Fatalf("arg0: %#v", p.Arguments[0])
	}
	if cfg.FindTable("CRM", "Public", "Customer") == nil {
		t.Fatal("normalized lookup failed")
	}
}

func TestErrorsWithPath(t *testing.T) {
	tests := []struct {
		name string
		yaml string
		want string
	}{
		{"坏语法", "metadata: [", "invalid YAML"},
		{"根不是映射", "- a", "root must be a mapping"},
		{"缺 metadata", "policies: {}", "'metadata' must be a mapping"},
		{"表缺 catalog", "metadata:\n  tables:\n    - schema: s\n      name: t\n      columns: [{name: a, type: text}]\n",
			"metadata.tables[0].catalog: required non-blank string is missing"},
		{"重复表", `
metadata:
  tables:
    - {catalog: crm, schema: public, name: customer, columns: [{name: a, type: text}]}
    - {catalog: CRM, schema: public, name: customer, columns: [{name: a, type: text}]}
policies: {}
`, "duplicate table 'CRM.public.customer'"},
		{"重复键", validYAML + "  udf: other", "invalid YAML"},
		{"未知策略", strings.Replace(validYAML, "policy: mask_phone", "policy: nope", 1),
			"columns[0].policy: unknown policy 'nope' (declared policies: [mask_phone])"},
		{"坏类型", strings.Replace(validYAML, "type: bigint", "type: money", 1),
			"columns[0].type: unsupported or malformed type declaration 'money'"},
		{"rowFilter 非 string", strings.Replace(validYAML, `rowFilter: "status = 'active'"`, "rowFilter: 12", 1),
			"rowFilter must be a string, but was Integer"},
		{"参数非标量", strings.Replace(validYAML, "arguments: [3, 4]", "arguments: [[3]]", 1),
			"must be a scalar (string, number or boolean)"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := LoadContent(tt.yaml, "metadata.yaml")
			if err == nil {
				t.Fatalf("want error")
			}
			if !strings.Contains(err.Error(), tt.want) {
				t.Fatalf("got %v, want contains %q", err, tt.want)
			}
			if me, ok := err.(*maskerr.Error); !ok || me.Code != maskerr.ConfigError {
				t.Fatalf("want CONFIG_ERROR: %v", err)
			}
		})
	}
}

func TestTypeVocabulary(t *testing.T) {
	ok := map[string]string{
		"boolean": "BOOLEAN", "bool": "BOOLEAN", "int2": "SMALLINT",
		"integer": "INTEGER", "int": "INTEGER", "int4": "INTEGER",
		"bigint": "BIGINT", "int8": "BIGINT", "real": "REAL", "float4": "REAL",
		"double precision": "DOUBLE", "float8": "DOUBLE",
		"numeric(10,2)": "DECIMAL", "decimal(5)": "DECIMAL",
		"char(8)": "CHAR", "character": "CHAR", "character varying(3)": "VARCHAR",
		"varchar(20)": "VARCHAR", "text": "VARCHAR", "date": "DATE",
		"timestamp(6)": "TIMESTAMP", "timestamp with time zone": "TIMESTAMP_WITH_LOCAL_TIME_ZONE",
		"time": "TIME", "timetz": "TIME_WITH_LOCAL_TIME_ZONE",
	}
	for decl, want := range ok {
		col, err := metadata.ParseColumn("c", decl)
		if err != nil {
			t.Fatalf("%s: %v", decl, err)
		}
		if col.TypeName != want {
			t.Fatalf("%s: got %s want %s", decl, col.TypeName, want)
		}
	}
	if col, _ := metadata.ParseColumn("c", "char"); *col.Precision != 1 {
		t.Fatal("char default precision should be 1")
	}
	if col, _ := metadata.ParseColumn("c", "numeric(5)"); col.Scale == nil || *col.Scale != 0 {
		t.Fatal("numeric(p) should imply scale 0")
	}
	bad := []string{"money", "timestamp(3) with time zone", "int(", "boolean(1)"}
	for _, decl := range bad {
		if _, err := metadata.ParseColumn("c", decl); err == nil {
			t.Fatalf("%s: want error", decl)
		}
	}
}
