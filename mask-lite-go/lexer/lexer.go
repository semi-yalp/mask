package lexer

import (
	"strings"
	"unicode"

	"io.masklite/go/dialect"
	"io.masklite/go/maskerr"
)

// 双字符运算符（最长匹配优先）。
var twoCharOps = []string{"<>", "!=", "<=", ">=", "||", "::", "=>"}

var oneCharOpSet = map[rune]bool{
	'+': true, '-': true, '*': true, '/': true, '%': true, '=': true,
	'<': true, '>': true, '(': true, ')': true, ',': true, '.': true,
	';': true, '[': true, ']': true, '{': true, '}': true, '|': true,
}

// Lex 把 src 切成 token 序列（最后一个恒为 EOF）。词法错误为
// PARSE_ERROR，消息带 Calcite 风格位置前缀 "Lexical error at Line N, Column M"。
func Lex(p *dialect.Profile, src string) ([]Token, error) {
	if p == nil {
		return nil, maskerr.New(maskerr.ConfigError, "lexer: nil dialect profile")
	}
	s := &scanner{prof: p, src: []rune(src), line: 1, col: 1}
	return s.run()
}

type scanner struct {
	prof *dialect.Profile
	src  []rune
	i    int
	line int
	col  int
}

func (s *scanner) run() ([]Token, error) {
	var toks []Token
	for {
		if err := s.skipTrivia(); err != nil {
			return nil, err
		}
		if s.i >= len(s.src) {
			toks = append(toks, Token{Kind: EOF, Pos: s.here()})
			return toks, nil
		}
		start := s.here()
		r := s.src[s.i]
		switch {
		case r == '\'':
			text, err := s.scanString(false)
			if err != nil {
				return nil, err
			}
			toks = append(toks, Token{Kind: String, Text: text, Pos: start})
		case r == '"':
			text, err := s.scanQuotedIdent()
			if err != nil {
				return nil, err
			}
			toks = append(toks, Token{Kind: QuotedIdent, Text: text, Pos: start})
		case r == '$' && s.isDollarQuote():
			text, err := s.scanDollarQuoted()
			if err != nil {
				return nil, err
			}
			toks = append(toks, Token{Kind: String, Text: text, Pos: start})
		case unicode.IsLetter(r) || r == '_':
			// E'...'：E 紧邻引号且前面不是单词字符时按转义串处理
			if (r == 'e' || r == 'E') && s.peekAt(1) == '\'' {
				s.advance() // 吃掉 E
				text, err := s.scanString(true)
				if err != nil {
					return nil, err
				}
				toks = append(toks, Token{Kind: String, Text: text, Pos: start})
			} else {
				text := s.scanWord()
				toks = append(toks, Token{Kind: Ident, Text: text, Pos: start})
			}
		case unicode.IsDigit(r):
			text := s.scanNumber()
			toks = append(toks, Token{Kind: Number, Text: text, Pos: start})
		case r == '?' || (r == '.' && unicode.IsDigit(s.peekAt(1))):
			if r == '?' {
				s.advance()
				text := "?"
				for s.i < len(s.src) && unicode.IsDigit(s.src[s.i]) {
					text += string(s.src[s.i])
					s.advance()
				}
				toks = append(toks, Token{Kind: Param, Text: text, Pos: start})
			} else {
				text := s.scanNumber()
				toks = append(toks, Token{Kind: Number, Text: text, Pos: start})
			}
		default:
			two := s.matchTwoCharOp()
			if two != "" {
				toks = append(toks, Token{Kind: Op, Text: two, Pos: start})
			} else if oneCharOpSet[r] {
				s.advance()
				toks = append(toks, Token{Kind: Op, Text: string(r), Pos: start})
			} else {
				return nil, s.lexError(start, "unexpected character '"+string(r)+"'")
			}
		}
	}
}

// skipTrivia 跳过空白与注释（-- 行注释、可嵌套 /* */ 块注释）。
func (s *scanner) skipTrivia() error {
	for s.i < len(s.src) {
		r := s.src[s.i]
		switch {
		case isSpace(r):
			s.advance()
		case r == '-' && s.peekAt(1) == '-':
			for s.i < len(s.src) && s.src[s.i] != '\n' {
				s.advance()
			}
		case r == '/' && s.peekAt(1) == '*':
			depth := 0
			openPos := s.here()
			for s.i < len(s.src) {
				if s.src[s.i] == '/' && s.peekAt(1) == '*' {
					depth++
					s.advance()
					s.advance()
				} else if s.src[s.i] == '*' && s.peekAt(1) == '/' {
					depth--
					s.advance()
					s.advance()
					if depth == 0 {
						break
					}
				} else {
					s.advance()
				}
			}
			if depth > 0 {
				return s.lexError(openPos, "unterminated block comment")
			}
		default:
			return nil
		}
	}
	return nil
}

func (s *scanner) here() Pos { return Pos{Line: s.line, Column: s.col} }

// advance 按 rune 推进；tab 按制表位 8 推进列号（对齐 Calcite SimpleCharStream）。
func (s *scanner) advance() {
	if s.i >= len(s.src) {
		return
	}
	r := s.src[s.i]
	s.i++
	if r == '\n' {
		s.line++
		s.col = 1
	} else if r == '\t' {
		s.col += 8 - (s.col-1)%8
	} else {
		s.col++
	}
}

func (s *scanner) peekAt(off int) rune {
	if s.i+off < len(s.src) {
		return s.src[s.i+off]
	}
	return 0
}

// isDollarQuote 判断当前位置是否 dollar 引用开头（$$ 或 $tag$）。
func (s *scanner) isDollarQuote() bool {
	if s.peekAt(1) == '$' {
		return true
	}
	if !unicode.IsLetter(s.peekAt(1)) {
		return false
	}
	j := s.i + 1
	for j < len(s.src) && (unicode.IsLetter(s.src[j]) || unicode.IsDigit(s.src[j]) || s.src[j] == '_') {
		j++
	}
	return j < len(s.src) && s.src[j] == '$'
}

func (s *scanner) scanDollarQuoted() (string, error) {
	start := s.here()
	j := s.i + 1
	for j < len(s.src) && (unicode.IsLetter(s.src[j]) || unicode.IsDigit(s.src[j]) || s.src[j] == '_') {
		j++
	}
	if j >= len(s.src) || s.src[j] != '$' {
		return "", s.lexError(start, "unexpected character '$'")
	}
	tag := string(s.src[s.i : j+1])
	content := s.src[j+1:]
	idx := strings.Index(string(content), tag)
	if idx < 0 {
		return "", s.lexError(start, "unterminated dollar-quoted string")
	}
	value := string(content[:idx])
	// 逐 rune 推进以保持行列号一致
	for s.i < j+1+idx+len(tag) {
		s.advance()
	}
	return value, nil
}

// scanString 扫描单引号串；escaped 为 true 时处理 E'...' 反斜杠转义。
// 返回解码后的值。
func (s *scanner) scanString(escaped bool) (string, error) {
	start := s.here()
	s.advance() // 开引号
	var b strings.Builder
	for {
		if s.i >= len(s.src) {
			return "", s.lexError(start, "unterminated string literal")
		}
		r := s.src[s.i]
		if escaped && r == '\\' && s.i+1 < len(s.src) {
			s.advance() // 反斜杠
			esc := s.src[s.i]
			switch esc {
			case 'b', 'f', 'n', 'r', 't':
				s.advance()
				switch esc {
				case 'b':
					b.WriteRune('\b')
				case 'f':
					b.WriteRune('\f')
				case 'n':
					b.WriteRune('\n')
				case 'r':
					b.WriteRune('\r')
				case 't':
					b.WriteRune('\t')
				}
			case 'x':
				// \xhh：1-2 位十六进制
				s.advance()
				v, n := 0, 0
				for n < 2 && s.i < len(s.src) && isHex(s.src[s.i]) {
					v = v*16 + hexVal(s.src[s.i])
					s.advance()
					n++
				}
				if n > 0 {
					b.WriteRune(rune(v))
				} else {
					b.WriteRune('x')
				}
			case '0', '1', '2', '3', '4', '5', '6', '7':
				// \ooo：1-3 位八进制
				v, n := int(esc-'0'), 1
				s.advance()
				for n < 3 && s.i < len(s.src) && s.src[s.i] >= '0' && s.src[s.i] <= '7' {
					v = v*8 + int(s.src[s.i]-'0')
					s.advance()
					n++
				}
				b.WriteRune(rune(v))
			default:
				s.advance()
				b.WriteRune(esc)
			}
			continue
		}
		if r == '\'' {
			s.advance()
			if s.i < len(s.src) && s.src[s.i] == '\'' {
				b.WriteRune('\'')
				s.advance()
				continue
			}
			return b.String(), nil
		}
		b.WriteRune(r)
		s.advance()
	}
}

func isHex(r rune) bool {
	return r >= '0' && r <= '9' || r >= 'a' && r <= 'f' || r >= 'A' && r <= 'F'
}

func hexVal(r rune) int {
	switch {
	case r >= '0' && r <= '9':
		return int(r - '0')
	case r >= 'a' && r <= 'f':
		return int(r-'a') + 10
	default:
		return int(r-'A') + 10
	}
}

func (s *scanner) scanQuotedIdent() (string, error) {
	start := s.here()
	s.advance() // 开引号
	var b strings.Builder
	for {
		if s.i >= len(s.src) {
			return "", s.lexError(start, "unterminated quoted identifier")
		}
		r := s.src[s.i]
		if r == '"' {
			s.advance()
			if s.i < len(s.src) && s.src[s.i] == '"' {
				b.WriteRune('"')
				s.advance()
				continue
			}
			return b.String(), nil
		}
		b.WriteRune(r)
		s.advance()
	}
}

// scanWord 扫描未引号标识符（单词字符：字母/数字/_/$）。
func (s *scanner) scanWord() string {
	start := s.i
	for s.i < len(s.src) && isWordChar(s.src[s.i]) {
		s.advance()
	}
	return string(s.src[start:s.i])
}

func isWordChar(r rune) bool {
	return unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' || r == '$'
}

// scanNumber 扫描数字：D+ [. D+] [E[+-]D+] 或 . D+ [E[+-]D+]（允许 1. 裸尾点）。
func (s *scanner) scanNumber() string {
	start := s.i
	for s.i < len(s.src) && unicode.IsDigit(s.src[s.i]) {
		s.advance()
	}
	if s.i < len(s.src) && s.src[s.i] == '.' {
		s.advance()
		for s.i < len(s.src) && unicode.IsDigit(s.src[s.i]) {
			s.advance()
		}
	}
	if s.i < len(s.src) && (s.src[s.i] == 'e' || s.src[s.i] == 'E') {
		j := s.i + 1
		if j < len(s.src) && (s.src[j] == '+' || s.src[j] == '-') {
			j++
		}
		if j < len(s.src) && unicode.IsDigit(s.src[j]) {
			s.advance() // e
			if s.i < len(s.src) && (s.src[s.i] == '+' || s.src[s.i] == '-') {
				s.advance()
			}
			for s.i < len(s.src) && unicode.IsDigit(s.src[s.i]) {
				s.advance()
			}
		}
	}
	return string(s.src[start:s.i])
}

func (s *scanner) matchTwoCharOp() string {
	if s.i+1 >= len(s.src) {
		return ""
	}
	two := string(s.src[s.i : s.i+2])
	for _, op := range twoCharOps {
		if two == op {
			s.advance()
			s.advance()
			return op
		}
	}
	return ""
}

func (s *scanner) lexError(pos Pos, msg string) error {
	return maskerr.Errorf(maskerr.ParseError,
		"Lexical error at Line %d, Column %d: %s", pos.Line, pos.Column, msg)
}

func isSpace(r rune) bool {
	switch r {
	case ' ', '\t', '\n', '\r', '\f', '\v':
		return true
	}
	return false
}
