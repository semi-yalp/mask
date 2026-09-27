package masklite

import (
	"strings"
	"testing"

	"io.masklite/go/maskerr"
)

// mlTestYAML 对齐 Java MaskLiteTest 的共用配置。
const mlTestYAML = `
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active'"
      columns:
        - { name: id, type: bigint }
        - { name: phone, type: varchar(20) }
        - { name: status, type: varchar(10) }
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

func mustMaskLite(t *testing.T) *MaskLite {
	t.Helper()
	m, err := FromYaml(mlTestYAML)
	if err != nil {
		t.Fatalf("FromYaml: %v", err)
	}
	return m
}

// 用例 1（对齐 rewritesWithMaskWrapperAndRowFilterInjection）。
func TestRewritesWithMaskWrapperAndRowFilterInjection(t *testing.T) {
	m := mustMaskLite(t)
	stmts, err := m.RewriteStatements("SELECT id, phone FROM customer WHERE id > 10")
	if err != nil {
		t.Fatalf("rewrite: %v", err)
	}
	if len(stmts) != 1 {
		t.Fatalf("want 1 statement, got %d", len(stmts))
	}
	s := stmts[0]
	if s.Ordinal != 1 || !s.Masked || !s.RowFiltered {
		t.Fatalf("flags: %+v", s)
	}
	if !strings.Contains(s.RewrittenSQL, "mask_phone(r.phone, 3, 4) AS phone") {
		t.Fatalf("missing mask call: %q", s.RewrittenSQL)
	}
	if !strings.Contains(s.RewrittenSQL, "WHERE status = 'active'") {
		t.Fatalf("missing row filter: %q", s.RewrittenSQL)
	}
	if s.RewrittenSQL == s.OriginalSQL {
		t.Fatalf("rewritten should differ from original")
	}
}

// 用例 2（对齐 rewriteJoinsIntoScript）：脚本拼接每语句一个分号。
func TestRewriteJoinsIntoScript(t *testing.T) {
	m := mustMaskLite(t)
	script, err := m.Rewrite("SELECT id FROM customer; SELECT phone FROM customer")
	if err != nil {
		t.Fatalf("rewrite: %v", err)
	}
	if got := strings.Count(script, ";"); got != 2 {
		t.Fatalf("want 2 semicolons, got %d: %q", got, script)
	}
}

// 用例 3（对齐 rejectsWriteStatementsFailClosed）。
func TestRejectsWriteStatementsFailClosed(t *testing.T) {
	m := mustMaskLite(t)
	_, err := m.RewriteStatements("INSERT INTO customer SELECT 1, 'x', 'y'")
	if err == nil {
		t.Fatal("want error")
	}
	if !strings.Contains(err.Error(), "only rewrites SELECT/WITH queries") {
		t.Fatalf("unexpected error: %v", err)
	}
	var me *maskerr.Error
	if !asMaskErr(err, &me) || me.Code != maskerr.UnsupportedStatement {
		t.Fatalf("want UNSUPPORTED_STATEMENT, got %v", err)
	}
}

// 用例 4（对齐 rejectsUnsupportedDialect）。
func TestRejectsUnsupportedDialect(t *testing.T) {
	_, err := FromYamlWithDialect(mlTestYAML, "trino")
	if err == nil {
		t.Fatal("want error")
	}
	if !strings.Contains(err.Error(), "mask-lite only supports: postgresql") {
		t.Fatalf("unexpected error: %v", err)
	}
}

// 用例 5（对齐 rejectsRowFilterReferencingUndeclaredColumn）：函数条件
// 在 registry 构建期即拒绝（CONFIG_ERROR，'other_function'）。
func TestRejectsRowFilterReferencingUndeclaredColumn(t *testing.T) {
	yaml := strings.Replace(mlTestYAML, `rowFilter: "status = 'active'"`, `rowFilter: "length(id) > 3"`, 1)
	_, err := FromYaml(yaml)
	if err == nil {
		t.Fatal("want error")
	}
	if !strings.Contains(err.Error(), "row filter") {
		t.Fatalf("unexpected error: %v", err)
	}
	if !strings.Contains(err.Error(), "'other_function' is not allowed") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func asMaskErr(err error, target **maskerr.Error) bool {
	if me, ok := err.(*maskerr.Error); ok {
		*target = me
		return true
	}
	return false
}

// TPC-DS 99 条离线改写回归（对齐 TpcdsOfflineRewriteTest）：
// 硬断言 99/99 且计数与 Java 打印值逐字一致（15 masked / 91 rowFiltered）。
func TestTpcdsOfflineRewrite(t *testing.T) {
	m, err := FromYamlFile("../testdata/tpcds/configs/tpcds-both.yaml")
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	success, masked, filtered, both := 0, 0, 0, 0
	var failures []string
	for i := 1; i <= 99; i++ {
		name := tpcdsQueryName(i)
		sql, readErr := readFile("../testdata/tpcds/queries/" + name)
		if readErr != nil {
			t.Fatalf("read %s: %v", name, readErr)
		}
		stmts, err := m.RewriteStatements(sql)
		if err != nil {
			failures = append(failures, name+": "+err.Error())
			continue
		}
		success++
		mk, rf := false, false
		for _, s := range stmts {
			if s.Masked {
				mk = true
			}
			if s.RowFiltered {
				rf = true
			}
		}
		if mk {
			masked++
		}
		if rf {
			filtered++
		}
		if mk && rf {
			both++
		}
	}
	if len(failures) > 0 {
		t.Fatalf("failures:\n%s", strings.Join(failures, "\n"))
	}
	t.Logf("TPC-DS offline rewrite: %d/99 success, %d masked, %d rowFiltered", success, masked, filtered)
	if success != 99 {
		t.Fatalf("want 99/99, got %d", success)
	}
	if masked != 15 {
		t.Fatalf("want 15 masked (Java baseline), got %d", masked)
	}
	if filtered != 91 {
		t.Fatalf("want 91 rowFiltered (Java baseline), got %d", filtered)
	}
	if both == 0 {
		t.Fatalf("want non-empty masked+rowFiltered overlap")
	}
}

// 多语句 + 失败原子性：任一语句失败 → 整批失败且无部分结果。
func TestAllOrNothing(t *testing.T) {
	m := mustMaskLite(t)
	if _, err := m.RewriteStatements("SELECT id FROM customer; DROP TABLE customer"); err == nil {
		t.Fatal("want error")
	}
}

// 行过滤注入但无脱敏：透传的是注入后文本（对齐 Java 语义）。
func TestPassthroughReturnsInjectedText(t *testing.T) {
	m := mustMaskLite(t)
	stmts, err := m.RewriteStatements("SELECT id, status FROM customer")
	if err != nil {
		t.Fatalf("rewrite: %v", err)
	}
	s := stmts[0]
	if s.Masked {
		t.Fatalf("unexpected mask: %+v", s)
	}
	if !s.RowFiltered {
		t.Fatalf("want rowFiltered: %+v", s)
	}
	if !strings.Contains(s.RewrittenSQL, "WHERE status = 'active'") {
		t.Fatalf("injected filter missing: %q", s.RewrittenSQL)
	}
	if strings.Contains(s.OriginalSQL, "status = 'active'") {
		t.Fatalf("original must not contain injection: %q", s.OriginalSQL)
	}
}
