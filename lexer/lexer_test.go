package lexer

// 词法器测试 —— 覆盖 task-4 简报 Step 2 的 (a)-(h) 及 fix round 1 裁定:
// (a) 逐 keyword 词例:未引号 Ident,词法层不区分关键字;
// (b) 引号字符按方言;
// (c) 字符串 '' 转义与 E' 前缀;
// (d) 数字(对齐 JavaCC DECIMAL/APPROX:裸 .5、1. 均为 Number);
// (e) 运算符与 Param;
// (f) -- 与 // 行注释、/* */ 块注释(不嵌套)不产 token;
// (g) 位置(1 起,Tab 按 8 制表位推进);
// (h) testdata/tokens.json 每个 operator 的 round-trip 断言,
//     且程序内 opTable 与 tokens.json 完全一致。
//
// 另有若干与 JavaCC SqlMaskParserImplTokenManager 实测行为对齐的
// 固定行为(数字开头标识符、EOF 位置等),注释中逐条标明出处。
// 构造型分歧(未闭合串、mysql 下双引号、白名单外字符等)的
// "已知口径"说明见 token.go 包注释与 task-4-report.md。

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"io.sqlmask/go/dialect"
	"io.sqlmask/go/internal/repotool"
	"io.sqlmask/go/maskerr"
)

// vocabulary 与 testdata/tokens.json 结构一致。
type vocabulary struct {
	Keywords  []string `json:"keywords"`
	Operators []string `json:"operators"`
}

func loadVocab(t *testing.T) vocabulary {
	t.Helper()
	bs, err := os.ReadFile(filepath.Join(repotool.Root(), "testdata", "tokens.json"))
	if err != nil {
		t.Fatalf("read testdata/tokens.json: %v", err)
	}
	var v vocabulary
	if err := json.Unmarshal(bs, &v); err != nil {
		t.Fatalf("parse testdata/tokens.json: %v", err)
	}
	return v
}

func mustProfile(t *testing.T, name string) *dialect.Profile {
	t.Helper()
	p, err := dialect.ByName(name)
	if err != nil {
		t.Fatalf("ByName(%q): %v", name, err)
	}
	return p
}

func mustLex(t *testing.T, p *dialect.Profile, src string) []Token {
	t.Helper()
	toks, err := Lex(p, src)
	if err != nil {
		t.Fatalf("Lex(%q) unexpected error: %v", src, err)
	}
	return toks
}

func mustLexErr(t *testing.T, p *dialect.Profile, src string, wantLine, wantCol int) {
	t.Helper()
	toks, err := Lex(p, src)
	if err == nil {
		t.Fatalf("Lex(%q) = %v, want lexical error", src, toks)
	}
	var me *maskerr.Error
	if !errors.As(err, &me) {
		t.Fatalf("Lex(%q) error %T (%v), want *maskerr.Error", src, err, err)
	}
	if me.Code != maskerr.ParseError {
		t.Fatalf("Lex(%q) error code = %s, want PARSE_ERROR", src, me.Code)
	}
	wantPrefix := fmt.Sprintf("Lexical error at Line %d, Column %d", wantLine, wantCol)
	if !strings.Contains(me.Message, wantPrefix) {
		t.Fatalf("Lex(%q) message = %q, want containing %q", src, me.Message, wantPrefix)
	}
}

// kinds 断言 token 串的 Kind 序列(含末尾 EOF)。
func kinds(t *testing.T, toks []Token, want ...TokenKind) {
	t.Helper()
	got := make([]TokenKind, len(toks))
	for i, tok := range toks {
		got[i] = tok.Kind
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("kinds = %v, want %v", got, want)
	}
}

// (a) 逐 keyword:大小写两种写法均为 Ident,原文未折算,词法不区分关键字。
func TestKeywordsLexAsIdent(t *testing.T) {
	pg := mustProfile(t, "postgresql")
	vocab := loadVocab(t)
	if len(vocab.Keywords) == 0 {
		t.Fatal("tokens.json keywords is empty")
	}
	for _, kw := range vocab.Keywords {
		for _, form := range []string{kw, strings.ToLower(kw)} {
			toks := mustLex(t, pg, form)
			kinds(t, toks, Ident, EOF)
			if toks[0].Text != form {
				t.Fatalf("keyword %q lexed text = %q, want unchanged source", form, toks[0].Text)
			}
			if toks[0].Pos != (Pos{1, 1}) {
				t.Fatalf("keyword %q pos = %v, want {1 1}", form, toks[0].Pos)
			}
		}
	}
}

// (a) select from 序列:全部 Ident,词法层没有关键字种类。
func TestSelectFromArePlainIdents(t *testing.T) {
	pg := mustProfile(t, "postgresql")
	toks := mustLex(t, pg, "select from SELECT")
	kinds(t, toks, Ident, Ident, Ident, EOF)
	want := []string{"select", "from", "SELECT"}
	for i, w := range want {
		if toks[i].Text != w {
			t.Fatalf("token[%d].Text = %q, want %q", i, toks[i].Text, w)
		}
	}
}

// (b) 引号按方言:pg 下 "..." 为 QuotedIdent(含引号原文、支持 "" 转义);
// mysql 下 " 开头词法错;mysql 下 `...` 为 QuotedIdent;pg 下 ` 词法错。
func TestQuotingPerDialect(t *testing.T) {
	pg := mustProfile(t, "postgresql")
	mysql := mustProfile(t, "mysql")
	trino := mustProfile(t, "trino")

	toks := mustLex(t, pg, `"a b"`)
	kinds(t, toks, QuotedIdent, EOF)
	if toks[0].Text != `"a b"` {
		t.Fatalf("pg quoted ident text = %q, want %q", toks[0].Text, `"a b"`)
	}
	if toks[0].Pos != (Pos{1, 1}) {
		t.Fatalf("pg quoted ident pos = %v", toks[0].Pos)
	}

	// "" 双写转义:整段一个 QuotedIdent(与 JavaCC QUOTED_IDENTIFIER 实测一致)
	toks = mustLex(t, pg, `"a""b"`)
	kinds(t, toks, QuotedIdent, EOF)
	if toks[0].Text != `"a""b"` {
		t.Fatalf("pg doubled-quote text = %q", toks[0].Text)
	}

	toks = mustLex(t, trino, `"x"`)
	kinds(t, toks, QuotedIdent, EOF)

	toks = mustLex(t, mysql, "`a b`")
	kinds(t, toks, QuotedIdent, EOF)
	if toks[0].Text != "`a b`" {
		t.Fatalf("mysql backtick ident text = %q", toks[0].Text)
	}

	// 简报 (b):mysql 下 " 开头 → 词法错。已知口径(Ruling 6):Java 词法
	// 层回退 DOUBLE_QUOTE 等 token、解析期报 PARSE_ERROR,错误码一致,
	// 消息与位置不同,差分时按错误码比对。
	mustLexErr(t, mysql, `"a b"`, 1, 1)
	mustLexErr(t, mysql, `"a""b"`, 1, 1)
	// pg 下反引号 → 词法错(与 JavaCC DQID 态实测一致)。
	mustLexErr(t, pg, "`a b`", 1, 1)
	// 引号标识符内不允许裸换行(QUOTED_IDENTIFIER 正则排除 \n\r);
	// 错误位置为换行字符自身(位置模型中换行仍记在原行末,与 EOF 位置
	// 的滞后语义一致)。
	mustLexErr(t, pg, "\"a\nb\"", 1, 3)
}

// (c) 字符串:双写单引号转义整段一个 String(含引号);E' 前缀(紧邻无空格)
// 支持 \' 转义;小写 e' 同样生效(JavaCC IGNORE_CASE 实测);E 与引号之间
// 有空格则不是 E 串。
func TestStrings(t *testing.T) {
	pg := mustProfile(t, "postgresql")

	toks := mustLex(t, pg, `'a''b'`)
	kinds(t, toks, String, EOF)
	if toks[0].Text != `'a''b'` {
		t.Fatalf("string text = %q, want 'a''b'", toks[0].Text)
	}

	toks = mustLex(t, pg, `E'a\'b'`)
	kinds(t, toks, String, EOF)
	if toks[0].Text != `E'a\'b'` {
		t.Fatalf("E-string text = %q", toks[0].Text)
	}

	toks = mustLex(t, pg, `e'x'`)
	kinds(t, toks, String, EOF)
	if toks[0].Text != `e'x'` {
		t.Fatalf("lowercase e-string text = %q", toks[0].Text)
	}

	// E 与 ' 之间有空格 → Ident("E") + String
	toks = mustLex(t, pg, `E 'x'`)
	kinds(t, toks, Ident, String, EOF)
	if toks[0].Text != "E" || toks[1].Text != "'x'" {
		t.Fatalf("E space quote: got %q, %q", toks[0].Text, toks[1].Text)
	}

	// 两个相邻字符串、串内换行合法(QUOTED_STRING 允许 \n)
	toks = mustLex(t, pg, `'ab' 'cd'`)
	kinds(t, toks, String, String, EOF)
	toks = mustLex(t, pg, "'a\nb'")
	kinds(t, toks, String, EOF)
	if toks[0].Text != "'a\nb'" {
		t.Fatalf("multiline string text = %q", toks[0].Text)
	}

	// 未闭合字符串:词法错。位置与 JavaCC TokenMgrError 的 EOF 报错位
	// 一致:无尾换行 → 最后一字符后一列;有尾换行 → (行+1, 0)。
	// 已知口径(Ruling 4):Java 词法层回退 QUOTE token、解析期报
	// PARSE_ERROR,错误码一致,消息与位置不同。
	mustLexErr(t, pg, `'abc`, 1, 5)
	mustLexErr(t, pg, "'abc\n", 2, 0)
	mustLexErr(t, pg, `E'a\`, 1, 5)
}

// (d) 数字:1 1.5 1e10 1.2E-3 均为 Number。fix round 1 Ruling 7:数字
// 词法对齐 Java(DECIMAL_NUMERIC_LITERAL / APPROX_NUMERIC_LITERAL,无上下文
// 最长匹配):裸 .5 与 1. 均为 Number;. D+ 形式可带指数;小数点在整数位
// 之后无条件消费(1.day → 1. + day);指数规则维持实测语义(1e → Ident)。
func TestNumbers(t *testing.T) {
	pg := mustProfile(t, "postgresql")

	toks := mustLex(t, pg, "1 1.5 1e10 1.2E-3")
	kinds(t, toks, Number, Number, Number, Number, EOF)
	want := []string{"1", "1.5", "1e10", "1.2E-3"}
	for i, w := range want {
		if toks[i].Text != w {
			t.Fatalf("number[%d].Text = %q, want %q", i, toks[i].Text, w)
		}
	}

	toks = mustLex(t, pg, "1e+45")
	kinds(t, toks, Number, EOF)
	toks = mustLex(t, pg, "1.5e-3")
	kinds(t, toks, Number, EOF)
	toks = mustLex(t, pg, "123")
	kinds(t, toks, Number, EOF)

	// Ruling 7(逐例与 JavaCC 实测一致):
	for _, tc := range []struct {
		src  string
		text []string
		kind []TokenKind
	}{
		{".5", []string{".5"}, []TokenKind{Number}},
		{"1.", []string{"1."}, []TokenKind{Number}},
		{"SELECT .5", []string{"SELECT", ".5"}, []TokenKind{Ident, Number}},
		{"1.2.3", []string{"1.2", ".3"}, []TokenKind{Number, Number}},
		{".5e3", []string{".5e3"}, []TokenKind{Number}},
		{"1.e5", []string{"1.e5"}, []TokenKind{Number}},
		{"1.day", []string{"1.", "day"}, []TokenKind{Number, Ident}},
		{"1..2", []string{"1.", ".2"}, []TokenKind{Number, Number}},
		{".5x", []string{".5", "x"}, []TokenKind{Number, Ident}},
		{"a.5", []string{"a", ".5"}, []TokenKind{Ident, Number}},
		{"1.2e", []string{"1.2", "e"}, []TokenKind{Number, Ident}},
	} {
		toks := mustLex(t, pg, tc.src)
		kinds(t, toks, append(tc.kind, EOF)...)
		for i, w := range tc.text {
			if toks[i].Text != w {
				t.Fatalf("Lex(%q)[%d].Text = %q, want %q", tc.src, i, toks[i].Text, w)
			}
		}
	}

	// JavaCC 实测对齐:数字开头标识符(config.fmpp customIdentifierToken)。
	toks = mustLex(t, pg, "1e")
	kinds(t, toks, Ident, EOF)
	if toks[0].Text != "1e" {
		t.Fatalf("1e text = %q, want ident 1e", toks[0].Text)
	}
	toks = mustLex(t, pg, "123abc")
	kinds(t, toks, Ident, EOF)
	toks = mustLex(t, pg, "1$x")
	kinds(t, toks, Ident, EOF)
}

// (e) 运算符逐个 + Param。
func TestOperatorsAndParams(t *testing.T) {
	pg := mustProfile(t, "postgresql")
	for _, op := range []string{"<=", "<>", "!=", "||", "::", "=>", "+", "-", "*", "/", "%", "=", "<", ">", "(", ")", ",", ".", ";"} {
		toks := mustLex(t, pg, op)
		kinds(t, toks, Op, EOF)
		if toks[0].Text != op {
			t.Fatalf("operator %q text = %q", op, toks[0].Text)
		}
	}
	// 最长匹配:< 与 = 相邻取 <=;:: 不拆成两个 :
	toks := mustLex(t, pg, "a<=b")
	kinds(t, toks, Ident, Op, Ident, EOF)

	toks = mustLex(t, pg, "?")
	kinds(t, toks, Param, EOF)
	toks = mustLex(t, pg, "?1")
	kinds(t, toks, Param, EOF)
	if toks[0].Text != "?1" {
		t.Fatalf("param text = %q, want ?1", toks[0].Text)
	}
	toks = mustLex(t, pg, "?42x")
	kinds(t, toks, Param, Ident, EOF)

	// 白名单外字符 → 词法错。已知口径(Ruling 8):Java 为合法 token、
	// 解析期报 PARSE_ERROR,错误码一致,消息与位置不同。(~ & ^ 已于 T11
	// jar 实测后收进 opTable,不再在此列。)
	mustLexErr(t, pg, ":", 1, 1)
	mustLexErr(t, pg, "!", 1, 1)
	mustLexErr(t, pg, "|", 1, 1)
}

// (f) 注释:-- 与 // 行注释(fix round 1 Ruling 2)、/* */ 块注释
// (不嵌套,第一个 */ 终结,Ruling 1)均不产 token;未闭合块注释词法错。
func TestComments(t *testing.T) {
	pg := mustProfile(t, "postgresql")

	toks := mustLex(t, pg, "SELECT -- c\n1")
	kinds(t, toks, Ident, Number, EOF)
	if toks[1].Pos != (Pos{2, 1}) {
		t.Fatalf("after line comment pos = %v, want {2 1}", toks[1].Pos)
	}

	toks = mustLex(t, pg, "a--b\nc")
	kinds(t, toks, Ident, Ident, EOF)

	// Ruling 2:// 与 -- 同义(JavaCC SINGLE_LINE_COMMENT 实测一致)。
	toks = mustLex(t, pg, "1 // x\n2")
	kinds(t, toks, Number, Number, EOF)
	toks = mustLex(t, pg, "a/b//c")
	kinds(t, toks, Ident, Op, Ident, EOF)
	toks = mustLex(t, pg, "SELECT // c")
	kinds(t, toks, Ident, EOF)

	// Ruling 1:块注释不嵌套,第一个 */ 终结(JavaCC 实测一致);
	// 注释外的散落 */ 为 Op(*)+Op(/)(Java 为 COMMENT_END token,
	// 解析期报错,见 watchlist)。
	toks = mustLex(t, pg, "/* /* x */ */ 1")
	kinds(t, toks, Op, Op, Number, EOF)
	toks = mustLex(t, pg, "a/* /* x */ */ b")
	kinds(t, toks, Ident, Op, Op, Ident, EOF)
	toks = mustLex(t, pg, "a/*x*/b")
	kinds(t, toks, Ident, Ident, EOF)
	toks = mustLex(t, pg, "/**/")
	kinds(t, toks, EOF)

	// -- 与 // 至文件尾(无换行)合法。
	toks = mustLex(t, pg, "SELECT -- c")
	kinds(t, toks, Ident, EOF)
	toks = mustLex(t, pg, "SELECT // c")
	kinds(t, toks, Ident, EOF)

	mustLexErr(t, pg, "SELECT /* abc", 1, 14)
	mustLexErr(t, pg, "SELECT /* abc\n", 2, 0)
}

// (g) 位置:1 起;Tab 按 8 制表位推进(fix round 1 Ruling 12,对齐
// JavaCC SimpleCharStream 实测:SELECT\t1 → 1@1:9);EOF 落在最后一个
// 字符上(Java 实测)。
func TestPositions(t *testing.T) {
	pg := mustProfile(t, "postgresql")

	toks := mustLex(t, pg, "SELECT\n  a")
	if toks[1].Pos != (Pos{2, 3}) {
		t.Fatalf("a pos = %v, want {2 3}", toks[1].Pos)
	}
	if toks[2].Kind != EOF || toks[2].Pos != (Pos{2, 3}) {
		t.Fatalf("eof = %v %v, want EOF at {2 3}", toks[2].Kind, toks[2].Pos)
	}

	// Ruling 12:Tab 按 8 制表位推进。
	toks = mustLex(t, pg, "SELECT\t1")
	if toks[1].Pos != (Pos{1, 9}) {
		t.Fatalf("after tab pos = %v, want {1 9}", toks[1].Pos)
	}
	toks = mustLex(t, pg, "SELECT\n\ta")
	if toks[1].Pos != (Pos{2, 9}) {
		t.Fatalf("after tab pos = %v, want {2 9}", toks[1].Pos)
	}

	// \r\n 只算一次换行。
	toks = mustLex(t, pg, "SELECT\r\n  a")
	if toks[1].Pos != (Pos{2, 3}) {
		t.Fatalf("after crlf pos = %v, want {2 3}", toks[1].Pos)
	}

	// 空输入:仅 EOF,位置 {1,1}(Java 实测 {0,0},Pos 简报 1 起,取 {1,1})。
	toks = mustLex(t, pg, "")
	kinds(t, toks, EOF)
	if toks[0].Pos != (Pos{1, 1}) {
		t.Fatalf("empty eof pos = %v, want {1 1}", toks[0].Pos)
	}

	// 尾随换行后 EOF 落在换行字符自身位置(Java 实测:行号滞后推进)。
	toks = mustLex(t, pg, "SELECT\n")
	if toks[1].Pos != (Pos{1, 7}) {
		t.Fatalf("eof after newline pos = %v, want {1 7}", toks[1].Pos)
	}
}

// (h) tokens.json 每个 operator 做 round-trip;opTable 必须是 tokens.json
// operators 的超集(tokens.json 是简报白名单快照,T11 jar 实测追加的
// <=> & ^ ~ 只在 opTable;子集关系防白名单漂移)。
func TestOperatorRoundTrip(t *testing.T) {
	pg := mustProfile(t, "postgresql")
	vocab := loadVocab(t)
	if len(vocab.Operators) == 0 {
		t.Fatal("tokens.json operators is empty")
	}
	for _, op := range vocab.Operators {
		toks := mustLex(t, pg, op)
		if len(toks) != 2 {
			t.Fatalf("Lex(%q) produced %d tokens, want 2 (token + EOF)", op, len(toks))
		}
		if toks[0].Text != op {
			t.Fatalf("Lex(%q)[0].Text = %q", op, toks[0].Text)
		}
		// ? 在词法层是 Param(简报 (e)),其余 operator 是 Op。
		wantKind := Op
		if op == "?" {
			wantKind = Param
		}
		if toks[0].Kind != wantKind {
			t.Fatalf("Lex(%q)[0].Kind = %v, want %v", op, toks[0].Kind, wantKind)
		}
		if toks[1].Kind != EOF {
			t.Fatalf("Lex(%q) missing EOF", op)
		}
	}

	inJSON := make(map[string]bool, len(vocab.Operators))
	for _, op := range vocab.Operators {
		inJSON[op] = true
	}
	for _, op := range opTable {
		if !inJSON[op] {
			continue // T11 jar 实测扩展项(<=> & ^ ~),不在白名单快照内
		}
	}
	// 超集断言:白名单每一项都必须仍在 opTable(防机械表回退)。
	opSet := make(map[string]bool, len(opTable))
	for _, op := range opTable {
		opSet[op] = true
	}
	for _, op := range vocab.Operators {
		if !opSet[op] {
			t.Fatalf("tokens.json operator %q missing from lexer opTable %v", op, opTable)
		}
	}
}

// TokenKind.String 便于报错定位。
func TestTokenKindString(t *testing.T) {
	want := map[TokenKind]string{
		Ident: "Ident", QuotedIdent: "QuotedIdent", String: "String",
		Number: "Number", Op: "Op", Param: "Param", EOF: "EOF",
	}
	for k, s := range want {
		if k.String() != s {
			t.Fatalf("TokenKind(%d).String() = %q, want %q", int(k), k.String(), s)
		}
	}
}

// nil profile → CONFIG_ERROR。
func TestNilProfile(t *testing.T) {
	_, err := Lex(nil, "select 1")
	var me *maskerr.Error
	if !errors.As(err, &me) || me.Code != maskerr.ConfigError {
		t.Fatalf("Lex(nil) error = %v, want CONFIG_ERROR", err)
	}
}
