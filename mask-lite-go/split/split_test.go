package split

import (
	"reflect"
	"testing"
)

func TestStatements(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want []string
	}{
		{"空输入", "", nil},
		{"纯空白", "   \n\t ", nil},
		{"单条", "SELECT 1", []string{"SELECT 1"}},
		{"两条", "a;b", []string{"a", "b"}},
		{"行注释内的分号", "a -- ; x\n;b", []string{"a -- ; x", "b"}},
		{"嵌套块注释", "a /* /* ; */ */;b", []string{"a /* /* ; */ */", "b"}},
		{"字符串双写", "SELECT 'it''s;ok'", []string{"SELECT 'it''s;ok'"}},
		{"转义串", `E'a\';b';b`, []string{`E'a\';b'`, "b"}},
		{"dollar 引用", "SELECT $$;$$;b", []string{"SELECT $$;$$", "b"}},
		{"带 tag 的 dollar 引用", "SELECT $tag$;$tag$;b", []string{"SELECT $tag$;$tag$", "b"}},
		{"双引号标识符", `SELECT "semi;colon"`, []string{`SELECT "semi;colon"`}},
		{"多语句 trim 与保序", "  a1 ;\n b2 ;", []string{"a1", "b2"}},
		{"尾随注释丢弃", "a; -- note", []string{"a"}},
		{"纯注释丢弃", "-- only", nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Statements(tt.in)
			if len(got) == 0 && len(tt.want) == 0 {
				return
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("got %#v, want %#v", got, tt.want)
			}
		})
	}
}
