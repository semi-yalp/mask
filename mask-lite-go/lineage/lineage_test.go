package lineage

import (
	"testing"

	"io.masklite/go/ast"
	"io.masklite/go/config"
	"io.masklite/go/dialect"
	"io.masklite/go/maskerr"
	"io.masklite/go/parser"
	"io.masklite/go/policy"
)

const cfgYAML = `
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - {name: id, type: bigint}
        - {name: phone, type: varchar(20)}
        - {name: email, type: varchar(100)}
        - {name: status, type: varchar(10)}
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

func analyzeSQL(t *testing.T, sql string) ([]Output, error) {
	t.Helper()
	cfg, err := config.LoadContent(cfgYAML, "metadata.yaml")
	if err != nil {
		t.Fatalf("config: %v", err)
	}
	eng := policy.NewEngine(cfg)
	a := NewAnalyzer(cfg, eng)
	p, err := parser.New(dialect.PostgreSQL, sql)
	if err != nil {
		t.Fatalf("lex: %v", err)
	}
	stmt, err := p.ParseStatement()
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	expanded, err := Expand(stmt)
	if err != nil {
		return nil, err
	}
	return a.Analyze(expanded)
}

func TestOriginsBasics(t *testing.T) {
	// 直接列引用 → RESOLVED
	out, err := analyzeSQL(t, "SELECT id, phone FROM customer")
	if err != nil {
		t.Fatalf("analyze: %v", err)
	}
	if out[0].Status != StatusResolved || out[0].Origins[0].Key.String() != "crm.public.customer.id" {
		t.Fatalf("col0: %+v", out[0])
	}
	// 常量 → NO_ORIGIN；表达式列 → RESOLVED derived
	out, err = analyzeSQL(t, "SELECT 42, id + 1 AS x FROM customer")
	if err != nil {
		t.Fatalf("analyze: %v", err)
	}
	if out[0].Status != StatusNoOrigin || out[0].Name != "EXPR$1" {
		t.Fatalf("const: %+v", out[0])
	}
	if out[1].Name != "x" || out[1].Status != StatusResolved {
		t.Fatalf("expr: %+v", out[1])
	}
	// COUNT(*) → NO_ORIGIN
	out, _ = analyzeSQL(t, "SELECT count(*) FROM customer")
	if out[0].Status != StatusNoOrigin {
		t.Fatalf("count: %+v", out[0])
	}
	// SELECT * 展开（声明序）
	out, _ = analyzeSQL(t, "SELECT * FROM customer")
	if len(out) != 4 || out[3].Name != "status" {
		t.Fatalf("star: %+v", out)
	}
	// 集合运算 origins 并集
	out, _ = analyzeSQL(t, "SELECT phone FROM customer UNION ALL SELECT 'x' AS phone FROM customer")
	if out[0].Status != StatusResolved || len(out[0].Origins) != 1 {
		t.Fatalf("setop: %+v", out[0])
	}
}

func TestFailClosed(t *testing.T) {
	// 投影中的标量子查询命中策略 → 整语句 UNKNOWN
	_, err := analyzeSQL(t, "SELECT (SELECT phone FROM customer) FROM customer")
	if err == nil || !isLineageUnknown(err) {
		t.Fatalf("want LINEAGE_UNKNOWN, got %v", err)
	}
	// 纯常量子查询安全 → NO_ORIGIN
	out, err := analyzeSQL(t, "SELECT (SELECT 1 FROM customer) FROM customer")
	if err != nil {
		t.Fatalf("safe subquery: %v", err)
	}
	if out[0].Status != StatusNoOrigin {
		t.Fatalf("pure subquery: %+v", out[0])
	}
	// 混合列：非子查询部分来源保留
	out, err = analyzeSQL(t, "SELECT id + (SELECT 1 FROM customer) AS x FROM customer")
	if err != nil {
		t.Fatalf("mixed: %v", err)
	}
	if out[0].Status != StatusResolved || out[0].Origins[0].Key.Column != "id" {
		t.Fatalf("mixed: %+v", out[0])
	}
	// 递归 CTE → LINEAGE_UNKNOWN
	_, err = analyzeSQL(t, "WITH RECURSIVE r AS (SELECT 1 AS x UNION ALL SELECT x FROM r) SELECT x FROM r")
	if err == nil || !isLineageUnknown(err) {
		t.Fatalf("recursive CTE: %v", err)
	}
	// 歧义列 → VALIDATION_ERROR
	_, err = analyzeSQL(t, "SELECT a FROM customer c1, customer c2")
	if err == nil || !isValidation(err) {
		t.Fatalf("want VALIDATION_ERROR, got %v", err)
	}
}

func TestCTEScoping(t *testing.T) {
	// CTE 引用内联后 origins 穿透到基表
	out, err := analyzeSQL(t, "WITH w AS (SELECT phone FROM customer) SELECT phone FROM w")
	if err != nil {
		t.Fatalf("cte: %v", err)
	}
	if out[0].Status != StatusResolved || out[0].Origins[0].Key.String() != "crm.public.customer.phone" {
		t.Fatalf("cte origins: %+v", out[0])
	}
	// ORDER BY 引用输出别名（表达式内）
	_, err = analyzeSQL(t, "SELECT id AS k FROM customer ORDER BY CASE WHEN k = 1 THEN 2 ELSE 3 END")
	if err != nil {
		t.Fatalf("order alias in expr: %v", err)
	}
}

func isLineageUnknown(err error) bool {
	me, ok := err.(*maskerr.Error)
	return ok && me.Code == maskerr.LineageUnknown
}

func isValidation(err error) bool {
	me, ok := err.(*maskerr.Error)
	return ok && me.Code == maskerr.ValidationError
}

// 展开 never mutates 输入树（简单健全性：AST 仍可渲染）。
func TestExpandDoesNotMutate(t *testing.T) {
	p, _ := parser.New(dialect.PostgreSQL, "WITH w AS (SELECT phone FROM customer) SELECT phone FROM w")
	stmt, _ := p.ParseStatement()
	if _, err := Expand(stmt); err != nil {
		t.Fatalf("expand: %v", err)
	}
	if _, ok := stmt.(*ast.With); !ok {
		t.Fatal("input tree changed")
	}
}
