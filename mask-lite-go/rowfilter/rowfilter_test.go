package rowfilter

import (
	"strings"
	"testing"

	"io.masklite/go/config"
	"io.masklite/go/maskerr"
)

func cfgWithFilter(t *testing.T, filter string) *config.LoadedConfig {
	t.Helper()
	yaml := `
metadata:
  tables:
    - catalog: tpcds
      schema: public
      name: t1
      rowFilter: "` + filter + `"
      columns: [{name: a, type: int}, {name: b, type: varchar(10)}]
    - catalog: tpcds
      schema: public
      name: t2
      columns: [{name: a, type: int}]
policies: {}
`
	c, err := config.LoadContent(yaml, "metadata.yaml")
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	return c
}

func TestWhitelistAcceptsAndRejects(t *testing.T) {
	ok := []string{"a = 1", "a > 1 AND b = 'x'", "NOT (a = 1 OR b = 2)", "a IN (1, 2, 3)",
		"b IS NULL", "b IS NOT NULL", "a IS DISTINCT FROM 1", "+a - 2 * 3 / 4 % 5 = 1"}
	for _, f := range ok {
		if _, err := Build(cfgWithFilter(t, f)); err != nil {
			t.Fatalf("%q: unexpected %v", f, err)
		}
	}
	bad := []struct{ f, want string }{
		{"length(a) > 1", "'other_function' is not allowed"},
		{"cast(a as varchar) = '1'", "'cast' is not allowed"},
		{"a BETWEEN 1 AND 2", "'between' is not allowed"},
		{"b LIKE 'x%'", "'like' is not allowed"},
		{"a IN (SELECT a FROM t2)", "must not contain subqueries"},
		{"EXISTS (SELECT 1)", "must not contain subqueries"},
		{"t1.a = 1", "must reference the table's columns directly, without qualification"},
		{"c = 1", "must only reference declared columns of the filtered table; 'c' is not one"},
		{"a = ?", "must not contain dynamic parameters"},
		{"a IS TRUE", "'is_true' is not allowed"},
		{"TRUE", ""}, // 布尔字面量本身合法
	}
	for _, tt := range bad {
		_, err := Build(cfgWithFilter(t, tt.f))
		if tt.want == "" {
			if err != nil {
				t.Fatalf("%q: unexpected %v", tt.f, err)
			}
			continue
		}
		if err == nil || !strings.Contains(err.Error(), tt.want) {
			t.Fatalf("%q: got %v, want contains %q", tt.f, err, tt.want)
		}
		if me, ok := err.(*maskerr.Error); !ok || me.Code != maskerr.ConfigError {
			t.Fatalf("%q: want CONFIG_ERROR: %v", tt.f, err)
		}
		if !strings.Contains(err.Error(), "table 'tpcds.public.t1': row filter") {
			t.Fatalf("%q: missing table prefix: %v", tt.f, err)
		}
	}
}

func TestIsControlledAndTemplate(t *testing.T) {
	r, err := Build(cfgWithFilter(t, "a = 1"))
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if !r.IsControlled("TPCDS", "public", "t1") || r.IsControlled("tpcds", "public", "t2") {
		t.Fatal("IsControlled wrong")
	}
	if r.ConditionTemplateOf("tpcds", "public", "t1") == nil {
		t.Fatal("template missing")
	}
}
