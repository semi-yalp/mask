// Package split 在顶层分号处切分多语句 SQL，逐语义移植 Java
// io.masklite.sql.SqlStatementSplitter：按 PostgreSQL 词法规则，单引号串
// （含 E'...' 反斜杠转义与 '' 双写）、双引号标识符、dollar 引用
// （$$...$$ / $tag$...$tag$）、-- 行注释与可嵌套 /* */ 块注释内部的分号
// 不产生切分点；空白/纯注释片段丢弃；语句保序；结果不带尾分号。
package split

import (
	"strings"
	"unicode"
)

// Statements 切分输入；空输入返回空切片。
func Statements(sql string) []string {
	if strings.TrimSpace(sql) == "" {
		return nil
	}
	src := []rune(sql)
	var out []string
	start := 0
	for i := 0; i < len(src); {
		switch src[i] {
		case '\'':
			i = skipSingleQuoted(src, i)
		case '"':
			i = skipDoubleQuoted(src, i)
		case '$':
			i = skipDollarQuoted(src, i)
		case '-':
			if i+1 < len(src) && src[i+1] == '-' {
				i = skipLineComment(src, i)
			} else {
				i++
			}
		case '/':
			if i+1 < len(src) && src[i+1] == '*' {
				i = skipBlockComment(src, i)
			} else {
				i++
			}
		case ';':
			addStatement(&out, src, start, i)
			i++
			start = i
		default:
			i++
		}
	}
	addStatement(&out, src, start, len(src))
	return out
}

func addStatement(out *[]string, src []rune, start, end int) {
	stmt := strings.TrimFunc(string(src[start:end]), func(r rune) bool {
		return isJavaWhitespace(r)
	})
	if containsCode(stmt) {
		*out = append(*out, stmt)
	}
}

// containsCode 判断片段是否含真实代码：只有空白与注释时返回 false，
// 因此末尾的 `-- note` 像空白一样被丢弃，而不是成为解析器读不懂的"语句"。
func containsCode(stmt string) bool {
	src := []rune(stmt)
	n := len(src)
	i := 0
	for i < n {
		c := src[i]
		switch {
		case c == '-' && i+1 < n && src[i+1] == '-':
			newline := runeIndex(src, i, '\n')
			if newline < 0 {
				return false
			}
			i = newline + 1
		case c == '/' && i+1 < n && src[i+1] == '*':
			depth := 1
			i += 2
			for i < n && depth > 0 {
				if src[i] == '/' && i+1 < n && src[i+1] == '*' {
					depth++
					i += 2
				} else if src[i] == '*' && i+1 < n && src[i+1] == '/' {
					depth--
					i += 2
				} else {
					i++
				}
			}
		case isJavaWhitespace(c):
			i++
		default:
			return true
		}
	}
	return false
}

func runeIndex(src []rune, from int, target rune) int {
	for i := from; i < len(src); i++ {
		if src[i] == target {
			return i
		}
	}
	return -1
}

func isWordChar(c rune) bool {
	return unicode.IsLetter(c) || unicode.IsDigit(c) || c == '_' || c == '$'
}

// escapedString 判断 quotePos 处的引号是否属于 E'...' 字面量：
// 前一个字符是 e/E 且再前一个不是单词字符（避免把 he'...' 当转义串）。
func escapedString(src []rune, quotePos int) bool {
	e := quotePos - 1
	if e < 0 {
		return false
	}
	c := src[e]
	if c != 'e' && c != 'E' {
		return false
	}
	return e == 0 || !isWordChar(src[e-1])
}

func skipSingleQuoted(src []rune, pos int) int {
	escaped := escapedString(src, pos)
	pos++ // 开引号
	for pos < len(src) {
		c := src[pos]
		if escaped && c == '\\' {
			pos++
			if pos < len(src) {
				pos++
			}
			continue
		}
		if c == '\'' {
			pos++
			if pos < len(src) && src[pos] == '\'' {
				pos++ // 双写引号仍在串内
				continue
			}
			return pos
		}
		pos++
	}
	return pos
}

func skipDoubleQuoted(src []rune, pos int) int {
	pos++
	for pos < len(src) {
		if src[pos] == '"' {
			pos++
			if pos < len(src) && src[pos] == '"' {
				pos++
				continue
			}
			return pos
		}
		pos++
	}
	return pos
}

// skipDollarQuoted 跳过 $$...$$ / $tag$...$tag$；未闭合则吞掉剩余输入。
func skipDollarQuoted(src []rune, pos int) int {
	tag, ok := dollarTagAt(src, pos)
	if !ok {
		return pos + 1
	}
	for i := pos + len(tag); i+len(tag) <= len(src); i++ {
		if string(src[i:i+len(tag)]) == tag {
			return i + len(tag)
		}
	}
	return len(src)
}

// dollarTagAt 返回 dollar 引用 tag 原文（如 "$$" 或 "$tag$"）；
// 不是引用开头时 ok=false（此时 '$' 只是普通字符）。
func dollarTagAt(src []rune, pos int) (string, bool) {
	if pos+1 < len(src) && src[pos+1] == '$' {
		return "$$", true
	}
	// $tag$：tag 首字符必须是字母，其余为字母/数字/下划线
	i := pos + 1
	if i >= len(src) || !unicode.IsLetter(src[i]) {
		return "", false
	}
	for i < len(src) && (unicode.IsLetter(src[i]) || unicode.IsDigit(src[i]) || src[i] == '_') {
		i++
	}
	if i < len(src) && src[i] == '$' {
		return string(src[pos : i+1]), true
	}
	return "", false
}

func skipLineComment(src []rune, pos int) int {
	if pos+1 >= len(src) || src[pos+1] != '-' {
		return pos + 1
	}
	newline := runeIndex(src, pos, '\n')
	if newline < 0 {
		return len(src)
	}
	return newline + 1
}

func skipBlockComment(src []rune, pos int) int {
	if pos+1 >= len(src) || src[pos+1] != '*' {
		return pos + 1
	}
	depth := 1
	pos += 2
	for pos < len(src) && depth > 0 {
		if src[pos] == '/' && pos+1 < len(src) && src[pos+1] == '*' {
			depth++
			pos += 2
		} else if src[pos] == '*' && pos+1 < len(src) && src[pos+1] == '/' {
			depth--
			pos += 2
		} else {
			pos++
		}
	}
	return pos
}

// isJavaWhitespace 对齐 java.lang.Character.isWhitespace 的常见取值：
// 空格、\t\n\v\f\r、文件/组/记录/单元分隔符（U+001C..U+001F）与 Unicode
// 空白（不含 U+00A0 不换行空格——Java 视其为非空白）。
func isJavaWhitespace(r rune) bool {
	switch r {
	case ' ', '\t', '\n', '\u000B', '\f', '\r', '\u001C', '\u001D', '\u001E', '\u001F':
		return true
	case '\u00A0':
		return false
	}
	return unicode.IsSpace(r)
}
