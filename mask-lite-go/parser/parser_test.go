package parser

import (
	"strings"
	"testing"

	"io.masklite/go/ast"
	"io.masklite/go/dialect"
	"io.masklite/go/render"
)

// parseRender 解析并渲染（render round-trip，golden 对齐 Java 实测输出）。
func parseRender(t *testing.T, src string) string {
	t.Helper()
	p, err := New(dialect.PostgreSQL, src)
	if err != nil {
		t.Fatalf("lex %q: %v", src, err)
	}
	stmt, err := p.ParseStatement()
	if err != nil {
		t.Fatalf("parse %q: %v", src, err)
	}
	return render.Statement(stmt)
}

func TestRenderGoldens(t *testing.T) {
	tests := []struct {
		name string
		src  string
		want string
	}{
		{"探针1 包装内层", "SELECT id, phone FROM customer WHERE id > 10",
			"SELECT id, phone\nFROM customer\nWHERE id > 10"},
		{"派生表注入形态", "SELECT * FROM customer WHERE status = 'active'",
			"SELECT *\nFROM customer\nWHERE status = 'active'"},
		{"集合运算换行", "(SELECT 1) UNION ALL (SELECT 2)",
			"SELECT 1\nUNION ALL\nSELECT 2"},
		{"JOIN 与逗号", "SELECT c.id FROM customer c JOIN customer c2 ON c.id = c2.id, customer c3",
			"SELECT c.id\nFROM customer AS c\nINNER JOIN customer AS c2 ON c.id = c2.id,\ncustomer AS c3"},
		{"LIMIT 转 FETCH", "SELECT id FROM customer LIMIT 3 OFFSET 1",
			"SELECT id\nFROM customer\nOFFSET 1 ROWS\nFETCH NEXT 3 ROWS ONLY"},
		{"BETWEEN ASYMMETRIC", "SELECT id FROM customer WHERE id BETWEEN 1 AND 2",
			"SELECT id\nFROM customer\nWHERE id BETWEEN ASYMMETRIC 1 AND 2"},
		{"函数规范大写", "SELECT coalesce(id, 0), count(DISTINCT status) FROM customer",
			"SELECT COALESCE(id, 0), COUNT(DISTINCT status)\nFROM customer"},
		{"窗口一行", "SELECT sum(id) OVER (PARTITION BY status ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) w FROM customer",
			"SELECT SUM(id) OVER (PARTITION BY status ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS w\nFROM customer"},
		{"ROLLUP", "SELECT status, count(*) FROM customer GROUP BY rollup(status)",
			"SELECT status, COUNT(*)\nFROM customer\nGROUP BY ROLLUP(status)"},
		{"引号标识符", `SELECT "customer".id FROM customer AS "customer"`,
			"SELECT \"customer\".id\nFROM customer AS \"customer\""},
		{"NOT 括号", "SELECT id FROM customer WHERE NOT (id > 1 OR status IS NULL) AND phone IS DISTINCT FROM 'x'",
			"SELECT id\nFROM customer\nWHERE NOT (id > 1 OR status IS NULL) AND phone IS DISTINCT FROM 'x'"},
		{"CASE 与 CAST", "SELECT CASE WHEN id > 1 THEN 'a' ELSE 'b' END, cast(id AS decimal(10,2)) FROM customer",
			"SELECT CASE WHEN id > 1 THEN 'a' ELSE 'b' END, CAST(id AS DECIMAL(10, 2))\nFROM customer"},
		{"ORDER BY 尾子句", "SELECT id FROM customer ORDER BY id DESC NULLS LAST",
			"SELECT id\nFROM customer\nORDER BY id DESC NULLS LAST"},
		{"子查询 IN", "SELECT id FROM customer WHERE id IN (SELECT id FROM customer)",
			"SELECT id\nFROM customer\nWHERE id IN (SELECT id\nFROM customer)"},
		{"EXISTS", "SELECT id FROM customer WHERE exists (SELECT 1 FROM customer)",
			"SELECT id\nFROM customer\nWHERE EXISTS (SELECT 1\nFROM customer)"},
		{"SUBSTRING FROM/FOR 归一", "SELECT substring(phone FROM 2 FOR 3) FROM customer",
			"SELECT SUBSTRING(phone, 2, 3)\nFROM customer"},
		{"嵌套派生表+UNION", "SELECT id FROM (SELECT id FROM customer UNION ALL SELECT id FROM customer) d",
			"SELECT id\nFROM (SELECT id\nFROM customer\nUNION ALL\nSELECT id\nFROM customer) AS d"},
		{"括号查询集合运算", "SELECT id FROM ((SELECT id FROM customer) EXCEPT (SELECT id FROM customer)) d",
			"SELECT id\nFROM (SELECT id\nFROM customer\nEXCEPT\nSELECT id\nFROM customer) AS d"},
		{"保留字别名加引号", "SELECT 1 AS \"order\"", "SELECT 1 AS \"order\""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parseRender(t, tt.src)
			if got != tt.want {
				t.Fatalf("got:\n%s\nwant:\n%s", got, tt.want)
			}
		})
	}
}

func TestParseErrors(t *testing.T) {
	for _, src := range []string{
		"SELECT FROM",          // 缺 select 项
		"SELECT 1 extra tokens",// 多余 token
		"SELECT TOP (5) * FROM customer", // TOP 全方言关闭
		"SELECT * FROM customer LIMIT 1 FETCH FIRST 1 ROWS ONLY", // LIMIT 与 FETCH 互斥
	} {
		p, err := New(dialect.PostgreSQL, src)
		if err != nil {
			continue // 词法错误也算拒绝
		}
		if _, err := p.ParseStatement(); err == nil {
			t.Fatalf("want parse error for %q", src)
		}
	}
	// UPDATE 等语句解析层返回 Unsupported 标记（错误由只读门/classify 给出）
	p, _ := New(dialect.PostgreSQL, "UPDATE customer SET id = 1")
	stmt, err := p.ParseStatement()
	if err != nil {
		t.Fatalf("UPDATE should parse to Unsupported: %v", err)
	}
	if _, ok := stmt.(*ast.Unsupported); !ok {
		t.Fatalf("want Unsupported, got %T", stmt)
	}
}

func TestWithParse(t *testing.T) {
	got := parseRender(t, "WITH a AS (SELECT 1 AS x) SELECT x FROM a ORDER BY x LIMIT 1")
	if !strings.Contains(got, "WITH a AS (SELECT 1 AS x)") {
		t.Fatalf("with render: %q", got)
	}
}
