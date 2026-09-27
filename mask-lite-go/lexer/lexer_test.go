package lexer

import (
	"strings"
	"testing"

	"io.masklite/go/dialect"
	"io.masklite/go/maskerr"
)

func mustLex(t *testing.T, src string) []Token {
	t.Helper()
	toks, err := Lex(dialect.PostgreSQL, src)
	if err != nil {
		t.Fatalf("Lex(%q): %v", src, err)
	}
	return toks
}

func kinds(toks []Token) []string {
	var out []string
	for _, tok := range toks {
		out = append(out, tok.Kind.String()+":"+tok.Text)
	}
	return out
}

func TestTokens(t *testing.T) {
	tests := []struct {
		name string
		src  string
		want []string
	}{
		{"关键字词法不区分", "select from", []string{"Ident:select", "Ident:from", "EOF:"}},
		{"双引号标识符", `"a b"`, []string{`QuotedIdent:a b`, "EOF:"}},
		{"字符串双写", `'a''b'`, []string{"String:a'b", "EOF:"}},
		{"转义串", `E'a\nb'`, []string{"String:a\nb", "EOF:"}},
		{"数字", "1 1.5 1e10 1.2E-3 .5", []string{
			"Number:1", "Number:1.5", "Number:1e10", "Number:1.2E-3", "Number:.5", "EOF:"}},
		{"双字符运算符", "<> != <= >= || :: =>", []string{
			"Op:<>", "Op:!=", "Op:<=", "Op:>=", "Op:||", "Op:::", "Op:=>", "EOF:"}},
		{"参数", "? ?1", []string{"Param:?", "Param:?1", "EOF:"}},
		{"注释不产 token", "a--x\nb/*x/*y*/z*/c", []string{"Ident:a", "Ident:b", "Ident:c", "EOF:"}},
		{"dollar 引用", "$$a'b$$ $tag$;$tag$", []string{"String:a'b", "String:;", "EOF:"}},
		{"位置", "SELECT\n  a", nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.want == nil {
				toks := mustLex(t, tt.src)
				if toks[1].Pos.Line != 2 || toks[1].Pos.Column != 3 {
					t.Fatalf("pos: %+v", toks[1].Pos)
				}
				return
			}
			got := kinds(mustLex(t, tt.src))
			if strings.Join(got, "|") != strings.Join(tt.want, "|") {
				t.Fatalf("got %v, want %v", got, tt.want)
			}
		})
	}
}

func TestLexError(t *testing.T) {
	_, err := Lex(dialect.PostgreSQL, `'oops`)
	if err == nil {
		t.Fatal("want error")
	}
	if !strings.Contains(err.Error(), "Lexical error at Line 1, Column 1") {
		t.Fatalf("unexpected: %v", err)
	}
	if me, ok := err.(*maskerr.Error); !ok || me.Code != maskerr.ParseError {
		t.Fatalf("want PARSE_ERROR: %v", err)
	}
}

func TestNilProfile(t *testing.T) {
	_, err := Lex(nil, "a")
	if err == nil || !strings.Contains(err.Error(), "CONFIG_ERROR") {
		t.Fatalf("want CONFIG_ERROR: %v", err)
	}
}
