package split

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"io.sqlmask/go/internal/repotool"
)

// TestStatements 表驱动覆盖 SqlStatementSplitter 的全部 javadoc 语义:
// 顶层分号切分;单引号串('' 双写转义、E'...' 反斜杠转义)、双引号标识符、
// $$...$$ / $tag$...$tag$ dollar 引用、-- 行注释、可嵌套 /* */ 块注释
// 内的分号不产生切分点;空白/纯注释片段丢弃;顺序保持;结果不含尾分号、
// 两端 trim;空输入返回空切片。
func TestStatements(t *testing.T) {
	tests := []struct {
		name string
		sql  string
		want []string
	}{
		{"空串", "", []string{}},
		{"纯空白", "  \t \r\n ", []string{}},
		{"顶层分号", "a;b", []string{"a", "b"}},
		{"行注释内的分号", "SELECT 1; -- ; no split\nSELECT 2;",
			[]string{"SELECT 1", "-- ; no split\nSELECT 2"}},
		{"嵌套块注释(纯注释丢弃)", "/* /* ; */ */", []string{}},
		{"嵌套块注释(语句内不切分)", "SELECT /* /* ; */ */ 1",
			[]string{"SELECT /* /* ; */ */ 1"}},
		{"单引号双写转义", `'it''s;ok'`, []string{`'it''s;ok'`}},
		{"E 字符串反斜杠转义", `E'a\';b'`, []string{`E'a\';b'`}},
		{"小写 e 字符串反斜杠转义", `e'a\';b'`, []string{`e'a\';b'`}},
		{"标识符结尾 E 不是转义串前缀", `LIKEE'a\';b'`, []string{`LIKEE'a\'`, "b'"}},
		{"dollar 引用 $$", `$$;$$`, []string{`$$;$$`}},
		{"dollar 引用 $tag$", `$tag$;$tag$`, []string{`$tag$;$tag$`}},
		{"未闭合 dollar 引用吞掉剩余", "$$ a ; b", []string{"$$ a ; b"}},
		{"数字开头的伪 dollar 标签", "$1$;$1$", []string{"$1$", "$1$"}},
		{"裸 $ 不是标签", "a $ ;b", []string{"a $", "b"}},
		{"双引号标识符", `"semi;colon"`, []string{`"semi;colon"`}},
		{"CRLF 两条一行(顺序与去分号)", "SELECT 1;\r\nSELECT 2;\r\n",
			[]string{"SELECT 1", "SELECT 2"}},
		{"尾随行注释按 containsCode 丢弃", "SELECT 1; -- note", []string{"SELECT 1"}},
		{"注释与空白混合片段丢弃", "SELECT 1; -- x\n/* ; */", []string{"SELECT 1"}},
		{"多语句两端 trim", "  SELECT 1 ;  SELECT 2 ;  ",
			[]string{"SELECT 1", "SELECT 2"}},
		{"语句间注释保留", "SELECT 1; /* c */ SELECT 2",
			[]string{"SELECT 1", "/* c */ SELECT 2"}},
		{"未闭合单引号吞掉剩余", "SELECT 'a;b", []string{"SELECT 'a;b"}},
		{"未闭合块注释吞掉剩余", "SELECT 1; /* ;", []string{"SELECT 1"}},
		{"除号与减号不是注释", "a/b - c;d", []string{"a/b - c", "d"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Statements(tt.sql)
			if !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("Statements(%q) = %#v, want %#v", tt.sql, got, tt.want)
			}
		})
	}
}

// TestStatements_TpcdsCrlfFile 端到端:读仓库语料 mask-engine/tpcds/queries/tpcds_crlf.sql,
// 断言恰好拆出 2 条语句(顺序保持、去分号、CRLF 随 trim 去除)。
func TestStatements_TpcdsCrlfFile(t *testing.T) {
	path := filepath.Join(repotool.Root(), "mask-engine", "tpcds", "queries", "tpcds_crlf.sql")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read corpus %s: %v", path, err)
	}
	got := Statements(string(data))
	if len(got) != 2 {
		t.Fatalf("Statements(tpcds_crlf.sql) len = %d (%#v), want exactly 2", len(got), got)
	}
	want := []string{
		"SELECT c_email_address FROM customer LIMIT 1",
		"SELECT c_phone FROM customer LIMIT 1",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Statements(tpcds_crlf.sql) = %#v, want %#v", got, want)
	}
}
