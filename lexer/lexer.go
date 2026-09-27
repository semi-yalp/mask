package lexer

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"io.sqlmask/go/dialect"
	"io.sqlmask/go/maskerr"
)

// Lex 按方言 Profile p 对 src 做词法分析,返回 token 序列(末尾恒为 EOF
// token)与词法错误。错误码恒为 maskerr.PARSE_ERROR,message 含
// "Lexical error at Line N, Column M"(前缀按简报逐字采用)。
func Lex(p *dialect.Profile, src string) ([]Token, error) {
	if p == nil {
		return nil, maskerr.Errorf(maskerr.ConfigError, "lexer: nil dialect profile")
	}
	var quote byte
	switch p.Quoting {
	case dialect.DoubleQuote:
		quote = '"'
	case dialect.BackTick:
		quote = '`'
	default:
		return nil, maskerr.Errorf(maskerr.ConfigError, "lexer: unsupported quoting %d", int(p.Quoting))
	}
	s := &scanner{src: src, quote: quote, line: 1}
	toks := make([]Token, 0, 16)
	for {
		if err := s.skipTrivia(); err != nil {
			return nil, err
		}
		if s.i >= len(s.src) {
			toks = append(toks, Token{Kind: EOF, Pos: s.eofPos()})
			return toks, nil
		}
		tok, err := s.next()
		if err != nil {
			return nil, err
		}
		toks = append(toks, tok)
	}
}

// scanner 手写扫描器。位置状态与 JavaCC SimpleCharStream 对齐:
// line/col 为最近读取字符的 1 起位置;换行的行号推进滞后到读取下一
// 字符时生效(prevLF/prevCR);Tab 记 1 列(简报规定,Java 实际按 8
// 制表位推进,差分复核项)。EOF token 落在最后一个字符上。
type scanner struct {
	src     string
	quote   byte // 引号标识符字符,按方言 '"' 或 '`'
	i       int  // 下一字节偏移
	line    int
	col     int
	prevLF  bool
	prevCR  bool
	lastLn  int // 最后读取字符的位置(EOF token 用)
	lastCol int
	hasLast bool
}

// read 消费并返回下一个 rune 及其 1 起位置;输入耗尽时 ok=false。
func (s *scanner) read() (r rune, pos Pos, ok bool) {
	if s.i >= len(s.src) {
		return 0, Pos{}, false
	}
	r, size := utf8.DecodeRuneInString(s.src[s.i:])
	s.i += size
	s.col++
	if s.prevLF {
		s.prevLF = false
		s.line++
		s.col = 1
	} else if s.prevCR {
		s.prevCR = false
		if r == '\n' {
			s.prevLF = true
		} else {
			s.line++
			s.col = 1
		}
	}
	switch r {
	case '\r':
		s.prevCR = true
	case '\n':
		s.prevLF = true
	}
	s.lastLn, s.lastCol, s.hasLast = s.line, s.col, true
	return r, Pos{Line: s.line, Column: s.col}, true
}

// peekIs 报告当前字节是否为 b(仅用于 ASCII 判定,UTF-8 续字节
// 不会与 ASCII 相等)。
func (s *scanner) peekIs(b byte) bool {
	return s.i < len(s.src) && s.src[s.i] == b
}

// nextIs 报告 s.i+1 处字节是否为 b(未消费当前字符时的前瞻)。
func (s *scanner) nextIs(b byte) bool {
	return s.i+1 < len(s.src) && s.src[s.i+1] == b
}

// eofPos 返回 EOF token 位置:与 JavaCC 实测一致,落在最后一个字符上
// (换行行号滞后);空输入为 {1,1}(Java 实测 {0,0},按简报 Pos 1 起)。
func (s *scanner) eofPos() Pos {
	if !s.hasLast {
		return Pos{Line: 1, Column: 1}
	}
	return Pos{Line: s.lastLn, Column: s.lastCol}
}

// errAt 在 pos 处构造词法错误。
func errAt(pos Pos, format string, args ...any) error {
	return maskerr.Errorf(maskerr.ParseError, "Lexical error at Line %d, Column %d: %s",
		pos.Line, pos.Column, fmt.Sprintf(format, args...))
}

// errAtEnd 在输入结尾构造词法错误(未闭合的串/注释/引号标识符)。
// 位置与 JavaCC TokenMgrError 的 EOF 报错实测一致:输入以换行收尾时为
// (行+1, 0);否则为最后一字符后一列。
func (s *scanner) errAtEnd(format string, args ...any) error {
	if s.prevLF || s.prevCR {
		return errAt(Pos{Line: s.line + 1, Column: 0}, format, args...)
	}
	return errAt(Pos{Line: s.line, Column: s.col + 1}, format, args...)
}

// skipTrivia 跳过空白(空格/\t/\n/\r/\f,与 JavaCC WHITESPACE 一致)、
// -- 行注释与可嵌套 /* */ 块注释(简报规定嵌套;Java 实际不嵌套,见报告)。
func (s *scanner) skipTrivia() error {
	for s.i < len(s.src) {
		switch c := s.src[s.i]; {
		case c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f':
			s.read()
		case c == '-' && s.nextIs('-'):
			s.skipLineComment()
		case c == '/' && s.nextIs('*'):
			if err := s.skipBlockComment(); err != nil {
				return err
			}
		default:
			return nil
		}
	}
	return nil
}

// skipLineComment 跳过 -- 至行尾(换行符留给空白跳过)或文件尾。
func (s *scanner) skipLineComment() {
	s.read()
	s.read()
	for s.i < len(s.src) {
		if c := s.src[s.i]; c == '\n' || c == '\r' {
			return
		}
		s.read()
	}
}

// skipBlockComment 跳过可嵌套的块注释。未闭合时报词法错(位置为输入
// 结尾,与 JavaCC 实测一致)。
func (s *scanner) skipBlockComment() error {
	s.read() // '/'
	s.read() // '*'
	depth := 1
	for s.i < len(s.src) {
		switch c := s.src[s.i]; {
		case c == '/' && s.nextIs('*'):
			s.read()
			s.read()
			depth++
		case c == '*' && s.nextIs('/'):
			s.read()
			s.read()
			depth--
			if depth == 0 {
				return nil
			}
		default:
			s.read()
		}
	}
	return s.errAtEnd("unterminated block comment")
}

// next 从当前位置词法化一个 token(当前位置必有输入)。
func (s *scanner) next() (Token, error) {
	r, pos, _ := s.read()
	switch {
	case (r == 'E' || r == 'e') && s.peekIs('\''):
		// E'/e' 前缀的 C 风格转义串(JavaCC IGNORE_CASE,实测小写 e 同效)。
		return s.lexEString(pos)
	case isLetter(r) || isDigit(r) || (r >= 0x80 && r <= 0xFF):
		return s.lexWordOrNumber(pos, r)
	case r == '\'':
		return s.lexString(pos)
	case r == rune(s.quote):
		return s.lexQuotedIdent(pos)
	case r == '?':
		return s.lexParam(pos)
	default:
		return s.lexOp(pos, r)
	}
}

// lexWordOrNumber 词法化以字母/数字起始的词,在「数字字面量」与「标识符」
// 两个候选间做与 JavaCC NFA 一致的最长匹配,等长时数字胜出(数值 token
// 在语法中定义先于 IDENTIFIER)。因此 1e10/1.2E-3 为 Number,而 1e/123abc
// 为 Ident(JavaCC 实测:标识符可数字开头,见 config.fmpp customIdentifierToken)。
//
// 数字规则(简报):D+ [. D+] [E[+-]D+],D 为 ASCII 数字;小数点后必须
// 有数字,故裸 .5 走运算符分支报词法错(简报规定;Java 的
// DECIMAL_NUMERIC_LITERAL 另允许 1. 与 .5,差分复核项)。
func (s *scanner) lexWordOrNumber(pos Pos, first rune) (Token, error) {
	start := s.i - utf8.RuneLen(first)
	identEnd := s.scanIdentTail()
	numEnd := -1
	if first >= '0' && first <= '9' {
		numEnd = s.scanNumberTail()
	}
	end := identEnd
	kind := Ident
	if numEnd >= 0 && numEnd >= identEnd {
		end = numEnd
		kind = Number
	}
	for s.i < end {
		s.read()
	}
	return Token{Kind: kind, Text: s.src[start:end], Pos: pos}, nil
}

// scanIdentTail 返回标识符候选的终点字节偏移(不含已消费的首字符):
// (LETTER|DIGIT|'$'|\u0080-\u00FF)*,对齐 config.fmpp 的 customIdentifierToken
// (PostgreSQL 风格:变音符号、非拉丁字母、$ 可作续字符)。
func (s *scanner) scanIdentTail() int {
	j := s.i
	for j < len(s.src) {
		r, size := utf8.DecodeRuneInString(s.src[j:])
		if !isLetter(r) && !isDigit(r) && r != '$' && !(r >= 0x80 && r <= 0xFF) {
			break
		}
		j += size
	}
	return j
}

// scanNumberTail 返回数字候选的终点字节偏移(不含已消费的首位数字):
// [. D+] [E[+-]D+];小数点后必须有数字,e/E 后必须有数字(可带符号)。
func (s *scanner) scanNumberTail() int {
	j := s.i
	for j < len(s.src) && isASCIIDigit(s.src[j]) {
		j++
	}
	if j+1 < len(s.src) && s.src[j] == '.' && isASCIIDigit(s.src[j+1]) {
		j += 2
		for j < len(s.src) && isASCIIDigit(s.src[j]) {
			j++
		}
	}
	if j < len(s.src) && (s.src[j] == 'e' || s.src[j] == 'E') {
		k := j + 1
		if k < len(s.src) && (s.src[k] == '+' || s.src[k] == '-') {
			k++
		}
		if k < len(s.src) && isASCIIDigit(s.src[k]) {
			for k < len(s.src) && isASCIIDigit(s.src[k]) {
				k++
			}
			j = k
		}
	}
	return j
}

// lexString 扫描单引号字符串:串内以双写单引号转义、不终结字符串,换行
// 合法(对齐 QUOTED_STRING)。未闭合时报词法错 —— Java 版此时回退为 QUOTE
// 单字符 token 由解析器报错,Go 版 TokenKind 无对应种别,记入差分风险。
func (s *scanner) lexString(pos Pos) (Token, error) {
	start := s.i - 1
	for {
		r, _, ok := s.read()
		if !ok {
			return Token{}, s.errAtEnd("unterminated string literal")
		}
		if r != '\'' {
			continue
		}
		// 双写转义仅在之后仍存在可收尾的引号时消费(最长完全匹配,
		// 与 JavaCC NFA 一致,如 ''' 产出 '' 而非吞尽后报错)。
		if s.peekIs('\'') && hasByteAfter(s.src, s.i+1, '\'') {
			s.read()
			continue
		}
		return Token{Kind: String, Text: s.src[start:s.i], Pos: pos}, nil
	}
}

// lexEString 扫描 E'/e' 前缀的 C 风格转义字符串:反斜杠转义任一字符,
// 双写单引号亦合法(对齐 C_STYLE_ESCAPED_STRING_LITERAL)。仅当 E/e 与引号
// 紧邻时生效(next 分支已保证)。未闭合时报词法错(同 lexString 差分风险)。
func (s *scanner) lexEString(pos Pos) (Token, error) {
	start := s.i - 1
	s.read() // 起始引号
	for {
		r, _, ok := s.read()
		if !ok {
			return Token{}, s.errAtEnd("unterminated E-string literal")
		}
		switch {
		case r == '\\':
			if _, _, ok := s.read(); !ok {
				return Token{}, s.errAtEnd("unterminated E-string literal")
			}
		case r == '\'':
			if s.peekIs('\'') && hasByteAfter(s.src, s.i+1, '\'') {
				s.read()
				continue
			}
			return Token{Kind: String, Text: s.src[start:s.i], Pos: pos}, nil
		}
	}
}

// lexQuotedIdent 扫描方言引号标识符:引号字符由 Profile 决定('"' 或
// '`'),内部以双写引号转义,不允许裸换行(QUOTED_IDENTIFIER /
// BACK_QUOTED_IDENTIFIER 正则排除 \n\r)。Text 含两端引号原文。
func (s *scanner) lexQuotedIdent(pos Pos) (Token, error) {
	start := s.i - 1
	for {
		r, rpos, ok := s.read()
		if !ok {
			return Token{}, s.errAtEnd("unterminated quoted identifier")
		}
		if r == '\n' || r == '\r' {
			return Token{}, errAt(rpos, "line break in quoted identifier")
		}
		if r != rune(s.quote) {
			continue
		}
		if s.peekIs(s.quote) && hasByteAfter(s.src, s.i+1, s.quote) {
			s.read()
			continue
		}
		return Token{Kind: QuotedIdent, Text: s.src[start:s.i], Pos: pos}, nil
	}
}

// lexParam 词法化动态参数 ? 或 ?N(N 为 ASCII 数字),Text 含数字原文。
func (s *scanner) lexParam(pos Pos) (Token, error) {
	start := s.i - 1
	for s.i < len(s.src) && isASCIIDigit(s.src[s.i]) {
		s.read()
	}
	return Token{Kind: Param, Text: s.src[start:s.i], Pos: pos}, nil
}

// lexOp 词法化运算符:先试二字符(最长匹配),再试单字符;白名单外的
// 字符报词法错。裸小数(. 后紧跟数字,无整数位)按简报报词法错。
func (s *scanner) lexOp(pos Pos, r rune) (Token, error) {
	if r >= 0x80 {
		return Token{}, errAt(pos, "unexpected character %q", r)
	}
	b := byte(r)
	if s.i < len(s.src) {
		if two := s.src[s.i-1 : s.i+1]; twoOp[two] {
			s.read()
			return Token{Kind: Op, Text: two, Pos: pos}, nil
		}
	}
	if b == '.' && s.peekIsDigit() {
		// 简报 (d):数字不收裸 .5(无整数位)。Java 的
		// DECIMAL_NUMERIC_LITERAL 接受 .5 与 1.,差分复核项。
		return Token{}, errAt(pos, "number literal cannot start with '.'")
	}
	if oneOp[b] {
		return Token{Kind: Op, Text: string(b), Pos: pos}, nil
	}
	return Token{}, errAt(pos, "unexpected character %q", r)
}

// peekIsDigit 报告当前字节是否为 ASCII 数字。
func (s *scanner) peekIsDigit() bool {
	return s.i < len(s.src) && isASCIIDigit(s.src[s.i])
}

// hasByteAfter 报告 src 中 offset 之后是否还存在字节 b,用于引号双写
// 转义与收尾的判定(保证只产出完全匹配,与 JavaCC NFA 最长匹配语义一致)。
func hasByteAfter(src string, offset int, b byte) bool {
	return offset <= len(src) && strings.IndexByte(src[offset:], b) >= 0
}

// isASCIIDigit 报告 b 是否为 ASCII 数字(数字字面量仅收 ASCII 数字,
// 与 JavaCC ["0"-"9"] 一致)。
func isASCIIDigit(b byte) bool { return b >= '0' && b <= '9' }

// isLetter 对齐 JavaCC #LETTER(config.fmpp/Parser.jj):
// '$'、A-Z、'_'、a-z、拉丁补充与希腊/CJK 等 Unicode 区段。
func isLetter(r rune) bool {
	switch {
	case r >= 'A' && r <= 'Z', r >= 'a' && r <= 'z':
		return true
	case r == '$' || r == '_':
		return true
	case r >= 0x00C0 && r <= 0x00D6, r >= 0x00D8 && r <= 0x00F6, r >= 0x00F8 && r <= 0x00FF:
		return true
	case r >= 0x0100 && r <= 0x1FFF, r >= 0x3040 && r <= 0x318F,
		r >= 0x3300 && r <= 0x337F, r >= 0x3400 && r <= 0x3D2D,
		r >= 0x4E00 && r <= 0x9FFF, r >= 0xF900 && r <= 0xFAFF:
		return true
	default:
		return false
	}
}

// isDigit 对齐 JavaCC #DIGIT:ASCII 0-9 与阿拉伯-印度文等 Unicode 数字区段。
func isDigit(r rune) bool {
	if r >= '0' && r <= '9' {
		return true
	}
	switch {
	case r >= 0x0660 && r <= 0x0669, r >= 0x06F0 && r <= 0x06F9,
		r >= 0x0966 && r <= 0x096F, r >= 0x09E6 && r <= 0x09EF,
		r >= 0x0A66 && r <= 0x0A6F, r >= 0x0AE6 && r <= 0x0AEF,
		r >= 0x0B66 && r <= 0x0B6F, r >= 0x0BE7 && r <= 0x0BEF,
		r >= 0x0C66 && r <= 0x0C6F, r >= 0x0CE6 && r <= 0x0CEF,
		r >= 0x0D66 && r <= 0x0D6F, r >= 0x0E50 && r <= 0x0E59,
		r >= 0x0ED0 && r <= 0x0ED9, r >= 0x1040 && r <= 0x1049:
		return true
	default:
		return false
	}
}
