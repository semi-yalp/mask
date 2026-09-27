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

// 用例 6（对齐 rendersSchemaQualifiedUdfNameSegmentWise）：schema 限定
// UDF 名逐段渲染。
func TestRendersSchemaQualifiedUdfNameSegmentWise(t *testing.T) {
	m, err := FromYaml(strings.Replace(mlTestYAML, "udf: mask_phone", "udf: public.mask_phone", 1))
	if err != nil {
		t.Fatalf("FromYaml: %v", err)
	}
	rewritten, err := m.Rewrite("SELECT phone FROM customer")
	if err != nil {
		t.Fatalf("rewrite: %v", err)
	}
	if !strings.Contains(rewritten, "public.mask_phone(r.phone, 3, 4)") {
		t.Fatalf("segment-wise rendering expected, got: %s", rewritten)
	}
}

// 用例 7（对齐 rejectsMalformedDottedUdfName）：空段/畸形点分名 fail-closed。
func TestRejectsMalformedDottedUdfName(t *testing.T) {
	for _, bad := range []string{"mask_phone.", ".mask_phone", "mask..phone"} {
		m, err := FromYaml(strings.Replace(mlTestYAML, "udf: mask_phone", "udf: "+bad, 1))
		if err != nil {
			t.Fatalf("load should succeed for %q: %v", bad, err)
		}
		_, err = m.Rewrite("SELECT phone FROM customer")
		if err == nil {
			t.Fatalf("udf name %q must be rejected", bad)
		}
		var me *maskerr.Error
		if asMaskErr(err, &me); !ok(me) || me.Code != maskerr.ConfigError {
			t.Fatalf("want CONFIG_ERROR for %q, got %v", bad, err)
		}
	}
}

func ok(me *maskerr.Error) bool { return me != nil }

// 用例 8（对齐 generatedColumnNamesSkipUserCollisions）：生成名跳过撞名位。
func TestGeneratedColumnNamesSkipUserCollisions(t *testing.T) {
	tricky := `
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - { name: id, type: bigint }
        - { name: mask_col_1, type: varchar(20) }
        - { name: phone, type: varchar(20) }
policies:
  mask_all:
    udf: mask_phone
    arguments: [3, 4]
columns:
  - { catalog: crm, schema: public, table: customer, column: mask_col_1, policy: mask_all }
  - { catalog: crm, schema: public, table: customer, column: phone, policy: mask_all }
`
	m, err := FromYaml(tricky)
	if err != nil {
		t.Fatalf("FromYaml: %v", err)
	}
	rewritten, err := m.Rewrite("SELECT mask_col_1, phone || 'x' FROM customer")
	if err != nil {
		t.Fatalf("rewrite: %v", err)
	}
	if !strings.Contains(rewritten, ") AS r (mask_col_1, mask_col_2)") {
		t.Fatalf("the EXPR$1 rename must skip the taken 'mask_col_1', got: %s", rewritten)
	}
}

func asMaskErr(err error, target **maskerr.Error) bool {
	if me, ok := err.(*maskerr.Error); ok {
		*target = me
		return true
	}
	return false
}

// expectedMasked / expectedRowFiltered 是 Java 回归锁定的位图
// （TpcdsOfflineRewriteTest.EXPECTED_*，逐条集合相等）。
var expectedMasked = map[string]bool{
	"q01.sql": true, "q04.sql": true, "q11.sql": true, "q23.sql": true,
	"q24.sql": true, "q30.sql": true, "q34.sql": true, "q46.sql": true,
	"q64.sql": true, "q68.sql": true, "q73.sql": true, "q74.sql": true,
	"q79.sql": true, "q81.sql": true, "q84.sql": true,
}

var expectedRowFiltered = map[string]bool{}

func init() {
	for _, q := range []string{
		"q01", "q02", "q03", "q04", "q05", "q06", "q07", "q08",
		"q10", "q11", "q12", "q13", "q14", "q15", "q16", "q17", "q18", "q19",
		"q20", "q21", "q22", "q23", "q24", "q25", "q26", "q27", "q29", "q30",
		"q31", "q32", "q33", "q34", "q35", "q36", "q37", "q38", "q39", "q40",
		"q42", "q43", "q45", "q46", "q47", "q48", "q49", "q50", "q51", "q52",
		"q53", "q54", "q55", "q56", "q57", "q58", "q59", "q60", "q61", "q62",
		"q63", "q64", "q65", "q66", "q67", "q68", "q69", "q70", "q71", "q72",
		"q73", "q74", "q75", "q76", "q77", "q78", "q79", "q80", "q81", "q82",
		"q83", "q84", "q85", "q86", "q87", "q89", "q91", "q92", "q94", "q95",
		"q97", "q98", "q99",
	} {
		expectedRowFiltered[q+".sql"] = true
	}
}

// TPC-DS 99 条离线改写回归（对齐 TpcdsOfflineRewriteTest）：
// 硬断言 99/99、15/91 计数与逐条位图集合相等、形状抽样。
func TestTpcdsOfflineRewrite(t *testing.T) {
	m, err := FromYamlFile("../testdata/tpcds/configs/tpcds-both.yaml")
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	success := 0
	var gotMasked, gotFiltered, failures []string
	rewrittenSample := map[string]string{}
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
		script := make([]string, 0, len(stmts))
		for _, s := range stmts {
			if s.Masked {
				mk = true
			}
			if s.RowFiltered {
				rf = true
			}
			script = append(script, s.RewrittenSQL)
		}
		rewrittenSample[name] = strings.Join(script, "\n\n")
		if mk {
			gotMasked = append(gotMasked, name)
		}
		if rf {
			gotFiltered = append(gotFiltered, name)
		}
	}
	if len(failures) > 0 {
		t.Fatalf("failures:\n%s", strings.Join(failures, "\n"))
	}
	t.Logf("TPC-DS offline rewrite: %d/99 success, %d masked, %d rowFiltered",
		success, len(gotMasked), len(gotFiltered))
	if success != 99 {
		t.Fatalf("want 99/99, got %d", success)
	}
	if len(gotMasked) != 15 {
		t.Fatalf("want 15 masked (Java baseline), got %d: %v", len(gotMasked), gotMasked)
	}
	if len(gotFiltered) != 91 {
		t.Fatalf("want 91 rowFiltered (Java baseline), got %d", len(gotFiltered))
	}
	for _, name := range gotMasked {
		if !expectedMasked[name] {
			t.Fatalf("unexpected masked query %s", name)
		}
	}
	for name := range expectedMasked {
		if !contains(gotMasked, name) {
			t.Fatalf("missing masked query %s", name)
		}
	}
	for _, name := range gotFiltered {
		if !expectedRowFiltered[name] {
			t.Fatalf("unexpected rowFiltered query %s", name)
		}
	}
	for name := range expectedRowFiltered {
		if !contains(gotFiltered, name) {
			t.Fatalf("missing rowFiltered query %s", name)
		}
	}
	// 形状抽样：q01 必含 mask_* UDF；q02 必嵌入某个过滤条件
	if !strings.Contains(rewrittenSample["q01.sql"], "mask_") {
		t.Fatalf("q01 must call a mask_* UDF: %s", rewrittenSample["q01.sql"])
	}
	q02 := rewrittenSample["q02.sql"]
	if !strings.Contains(q02, "ca_country = 'United States'") &&
		!strings.Contains(q02, "d_year <= 2002") &&
		!strings.Contains(q02, "c_birth_year >= 1930") {
		t.Fatalf("q02 must embed a filter: %s", q02)
	}
}

func contains(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
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
