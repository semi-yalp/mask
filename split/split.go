// Package split 按顶层分号拆分多语句 SQL。
//
// 逐语义移植 Java 版 SqlStatementSplitter,遵循 PostgreSQL 词法规则:
// 单引号字符串(含 '' 双写转义与 E'...' 反斜杠转义)、双引号标识符、
// dollar 引用字符串($$...$$、$tag$...$tag$)以及注释(-- 行注释与
// 可嵌套的 /* */ 块注释)内部永远不产生切分点。空白与纯注释片段被丢弃;
// 语句顺序保持不变;返回的语句不带尾分号,且已去除两端空白。
package split

import (
	"strings"
	"unicode"
	"unicode/utf8"
)

// Statements 按顶层分号切分 sql;输入为空或纯空白时返回空切片。
func Statements(sql string) []string {
	statements := make([]string, 0)
	if isBlank(sql) {
		return statements
	}
	cursor := &cursor{sql: sql, length: len(sql)}
	statementStart := 0
	for cursor.hasNext() {
		switch cursor.peek() {
		case '\'':
			cursor.skipSingleQuoted()
		case '"':
			cursor.skipDoubleQuoted()
		case '$':
			cursor.skipDollarQuoted()
		case '-':
			cursor.skipLineComment()
		case '/':
			cursor.skipBlockComment()
		case ';':
			addStatement(&statements, sql, statementStart, cursor.pos)
			statementStart = cursor.next()
			cursor.advance()
		default:
			cursor.advance()
		}
	}
	addStatement(&statements, sql, statementStart, len(sql))
	return statements
}

func addStatement(statements *[]string, sql string, start, end int) {
	statement := javaTrim(sql[start:end])
	if containsCode(statement) {
		*statements = append(*statements, statement)
	}
}

// containsCode 报告片段是否含有注释与空白之外的真实代码。
// 只含空白与注释的片段返回 false —— 最后一个分号之后的 "-- note"
// 与空白片段一样被丢弃,而不是变成解析器无法阅读的"语句"。
func containsCode(statement string) bool {
	length := len(statement)
	i := 0
	for i < length {
		c := statement[i]
		if c == '-' && charAtIs(statement, i+1, '-') {
			// 行注释:跳到换行之后;到结尾都没有换行则整段无代码。
			rel := strings.IndexByte(statement[i:], '\n')
			if rel < 0 {
				return false
			}
			i += rel + 1
		} else if c == '/' && charAtIs(statement, i+1, '*') {
			// 可嵌套块注释:深度归零才出注释。
			depth := 1
			i += 2
			for i < length && depth > 0 {
				if charAtIs(statement, i, '/') && charAtIs(statement, i+1, '*') {
					depth++
					i += 2
				} else if charAtIs(statement, i, '*') && charAtIs(statement, i+1, '/') {
					depth--
					i += 2
				} else {
					i++
				}
			}
		} else {
			r := rune(c)
			width := 1
			if c >= utf8.RuneSelf {
				r, width = utf8.DecodeRuneInString(statement[i:])
			}
			if !isJavaWhitespace(r) {
				return true
			}
			i += width
		}
	}
	return false
}

// charAtIs 越界安全的单字节比较(对应 Java 版 charAtIs)。
func charAtIs(s string, index int, expected byte) bool {
	return index >= 0 && index < len(s) && s[index] == expected
}

// isBlank 等价于 Java String#isBlank:空串或仅含 Java 空白字符。
func isBlank(s string) bool {
	for i := 0; i < len(s); {
		r, width := utf8.DecodeRuneInString(s[i:])
		if !isJavaWhitespace(r) {
			return false
		}
		i += width
	}
	return true
}

// javaTrim 等价于 Java String#trim:去除两端码点 <= U+0020 的字符。
// 非 ASCII 字符的 UTF-8 字节均 >= 0x80,按字节裁剪不会切入多字节序列。
func javaTrim(s string) string {
	start := 0
	for start < len(s) && s[start] <= 0x20 {
		start++
	}
	end := len(s)
	for end > start && s[end-1] <= 0x20 {
		end--
	}
	return s[start:end]
}

// isJavaWhitespace 逐位对应 java.lang.Character#isWhitespace(char):
// Zs/Zl/Zp 分隔符(排除 U+00A0、U+2007、U+202F 三种不换行空格),
// 外加 \t、\n、\v、\f、\r 与 U+001C..U+001F。
func isJavaWhitespace(r rune) bool {
	switch r {
	case '\t', '\n', '\v', '\f', '\r', 0x001C, 0x001D, 0x001E, 0x001F:
		return true
	case 0x00A0, 0x2007, 0x202F:
		return false
	}
	return unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp)
}

// cursor 是主扫描游标。pos 为字节偏移且恒落在 rune 边界上:peek/advance
// 按 rune(对应 Java 的 char)推进;charAt/charAtIs 的向前看只比较 ASCII
// 字节,而多字节序列的每个字节都 >= 0x80,故判定结果与 Java 一致。
type cursor struct {
	sql    string
	length int
	pos    int
}

func (c *cursor) hasNext() bool { return c.pos < c.length }

func (c *cursor) peek() rune {
	r, _ := utf8.DecodeRuneInString(c.sql[c.pos:])
	return r
}

func (c *cursor) advance() {
	if c.pos >= c.length {
		return
	}
	_, width := utf8.DecodeRuneInString(c.sql[c.pos:])
	c.pos += width
}

// next 返回当前位置之后一字节的偏移(';' 恒为单字节 ASCII)。
func (c *cursor) next() int { return c.pos + 1 }

// charAt 越界时返回 0(对应 Java 版返回 '\0')。
func (c *cursor) charAt(index int) byte {
	if index >= 0 && index < c.length {
		return c.sql[index]
	}
	return 0
}

func isWordChar(r rune) bool {
	return unicode.IsLetter(r) || unicode.IsDigit(r) || r == '_' || r == '$'
}

// isWordCharBefore 判断偏移 index 上的字符是否为单词字符。index 紧邻一个
// ASCII 字节之前,必为 rune 边界,故可安全解码;index < 0 对应 Java 的
// charAt(-1) == '\0',不是单词字符。
func (c *cursor) isWordCharBefore(index int) bool {
	if index < 0 {
		return false
	}
	r, _ := utf8.DecodeLastRuneInString(c.sql[:index+1])
	return isWordChar(r)
}

// escapedString 报告 quotePos 处的引号是否为 E'...' 字面量的引号:
// 前一字符为 e 或 E,且再往前不是单词字符(否则是标识符的一部分)。
func (c *cursor) escapedString(quotePos int) bool {
	e := quotePos - 1
	if e < 0 {
		return false
	}
	if ch := c.sql[e]; ch != 'e' && ch != 'E' {
		return false
	}
	return !c.isWordCharBefore(e - 1)
}

func (c *cursor) skipSingleQuoted() {
	escaped := c.escapedString(c.pos)
	c.advance() // 开引号
	for c.hasNext() {
		ch := c.peek()
		if escaped && ch == '\\' {
			// 反斜杠转义:连同其后的一个字符一并跳过。
			c.advance()
			if c.hasNext() {
				c.advance()
			}
			continue
		}
		if ch == '\'' {
			c.advance()
			if c.hasNext() && c.peek() == '\'' {
				c.advance() // 双写引号仍在字符串内
				continue
			}
			return
		}
		c.advance()
	}
}

func (c *cursor) skipDoubleQuoted() {
	c.advance()
	for c.hasNext() {
		if c.peek() == '"' {
			c.advance()
			if c.hasNext() && c.peek() == '"' {
				c.advance() // 双写引号仍在标识符内
				continue
			}
			return
		}
		c.advance()
	}
}

// skipDollarQuoted 跳过 $$...$$ / $tag$...$tag$ dollar 引用字符串。
func (c *cursor) skipDollarQuoted() {
	tag := c.dollarTagAt(c.pos)
	if tag == "" {
		c.advance()
		return
	}
	contentStart := c.pos + len(tag)
	end := strings.Index(c.sql[contentStart:], tag)
	if end < 0 {
		c.pos = c.length // 未闭合的 dollar 引用吞掉剩余输入
		return
	}
	c.pos = contentStart + end + len(tag)
}

// dollarTagAt 返回偏移 at 处的 dollar 引用标签($$ 或 $tag$),
// 不是合法标签时返回空串。
func (c *cursor) dollarTagAt(at int) string {
	if c.charAt(at+1) == '$' {
		return "$$"
	}
	rel := strings.IndexByte(c.sql[at+1:], '$')
	if rel < 0 {
		return ""
	}
	closeIdx := at + 1 + rel
	inner := c.sql[at+1 : closeIdx]
	if inner == "" {
		return ""
	}
	for i, r := range inner {
		if i == 0 {
			if !unicode.IsLetter(r) {
				return ""
			}
			continue
		}
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) && r != '_' {
			return ""
		}
	}
	return c.sql[at : closeIdx+1]
}

func (c *cursor) skipLineComment() {
	if c.charAt(c.pos+1) != '-' {
		c.advance() // 单个 '-' 是运算符,不是注释
		return
	}
	if rel := strings.IndexByte(c.sql[c.pos:], '\n'); rel < 0 {
		c.pos = c.length
	} else {
		c.pos += rel + 1
	}
}

// skipBlockComment 跳过可嵌套的块注释。
func (c *cursor) skipBlockComment() {
	if c.charAt(c.pos+1) != '*' {
		c.advance() // 单个 '/' 是除号,不是注释
		return
	}
	depth := 1
	c.pos += 2
	for c.hasNext() && depth > 0 {
		switch {
		case c.peek() == '/' && c.charAt(c.pos+1) == '*':
			depth++
			c.pos += 2
		case c.peek() == '*' && c.charAt(c.pos+1) == '/':
			depth--
			c.pos += 2
		default:
			c.advance()
		}
	}
}
