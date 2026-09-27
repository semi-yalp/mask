// Command tokenextract 从 JavaCC 生成的 SqlMaskParserImplConstants.java 的
// tokenImage 数组机械提取词汇表,写出 testdata/tokens.json(生成物,随代码提交):
//
//   - 形如 "<X>" 的字面镜像且 X 全为大写字母/下划线 → keywords
//     (按 tokenImage 出现顺序,即 JavaCC 语法中的定义顺序);
//   - 标点符号串且属于下方 operatorWhitelist → operators(按白名单顺序);
//   - 其余条目(正则型 token、空白字符、注释符、白名单外标点)忽略。
//
// 若常量文件缺失,先在仓库根执行一次:
//
//	mvn -pl mask-sqlparser -am generate-sources -q
//
// 再运行本工具:
//
//	go run ./cmd/tokenextract
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"io.sqlmask/go/internal/repotool"
)

// operatorWhitelist 运算符白名单(task-4 简报逐字给定);提取时只保留
// tokenImage 中出现过的条目,顺序即本表顺序。
var operatorWhitelist = []string{
	"+", "-", "*", "/", "%", "=", "<>", "!=", "<", "<=", ">", ">=",
	"(", ")", ",", ".", ";", "||", "::", "=>", "?",
}

// keywordRe 关键字形状:全大写字母/下划线(简报提取规则)。
var keywordRe = regexp.MustCompile(`^[A-Z_]+$`)

// vocabulary tokens.json 的结构。
type vocabulary struct {
	Keywords  []string `json:"keywords"`
	Operators []string `json:"operators"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "tokenextract:", err)
		os.Exit(1)
	}
}

func run() error {
	root := repotool.Root()
	constPath := filepath.Join(root,
		"mask-sqlparser", "target", "generated-sources", "javacc",
		"io", "sqlmask", "parser", "SqlMaskParserImplConstants.java")
	data, err := os.ReadFile(constPath)
	if err != nil {
		return fmt.Errorf("read JavaCC constants file (run 'mvn -pl mask-sqlparser -am generate-sources -q' at repo root first): %w", err)
	}
	images, err := extractTokenImage(string(data))
	if err != nil {
		return fmt.Errorf("%s: %w", constPath, err)
	}
	keywords, operators, err := classify(images)
	if err != nil {
		return err
	}

	out := vocabulary{Keywords: keywords, Operators: operators}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false) // 保留 <、>、& 原字符,便于 diff 审阅
	enc.SetIndent("", "  ")
	if err := enc.Encode(out); err != nil {
		return err
	}
	bs := buf.Bytes()
	outPath := filepath.Join(root, "testdata", "tokens.json")
	if err := os.MkdirAll(filepath.Dir(outPath), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(outPath, bs, 0o644); err != nil {
		return err
	}
	fmt.Printf("tokenextract: %d keywords, %d operators -> %s\n",
		len(keywords), len(operators), outPath)
	return nil
}

// extractTokenImage 扫描 tokenImage 数组,按序返回各条目解码后的值。
func extractTokenImage(src string) ([]string, error) {
	const marker = "String[] tokenImage = {"
	idx := strings.Index(src, marker)
	if idx < 0 {
		return nil, fmt.Errorf("tokenImage array not found")
	}
	i := idx + len(marker)
	images := make([]string, 0, 1024)
	for {
		for i < len(src) && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r' || src[i] == ',') {
			i++
		}
		if i >= len(src) {
			return nil, fmt.Errorf("unterminated tokenImage array")
		}
		if src[i] == '}' {
			return images, nil
		}
		if src[i] != '"' {
			return nil, fmt.Errorf("unexpected character %q at offset %d", src[i], i)
		}
		lit, next, err := scanJavaString(src, i)
		if err != nil {
			return nil, err
		}
		images = append(images, lit)
		i = next
	}
}

// scanJavaString 扫描 src[i:] 起始的 Java 字符串字面量(src[i] == '"'),
// 返回解码后的值与结束后的下一偏移。
func scanJavaString(src string, i int) (string, int, error) {
	i++
	var b strings.Builder
	for i < len(src) {
		switch c := src[i]; c {
		case '"':
			return b.String(), i + 1, nil
		case '\\':
			i++
			if i >= len(src) {
				return "", 0, fmt.Errorf("dangling escape")
			}
			switch e := src[i]; e {
			case 'b':
				b.WriteByte('\b')
			case 't':
				b.WriteByte('\t')
			case 'n':
				b.WriteByte('\n')
			case 'f':
				b.WriteByte('\f')
			case 'r':
				b.WriteByte('\r')
			case '"':
				b.WriteByte('"')
			case '\'':
				b.WriteByte('\'')
			case '\\':
				b.WriteByte('\\')
			case 'u':
				if i+5 > len(src) {
					return "", 0, fmt.Errorf("bad unicode escape")
				}
				v, err := strconv.ParseUint(src[i+1:i+5], 16, 32)
				if err != nil {
					return "", 0, fmt.Errorf("bad unicode escape \\%s: %w", src[i+1:i+5], err)
				}
				b.WriteRune(rune(v))
				i += 4
			default:
				return "", 0, fmt.Errorf("unsupported escape \\%c", e)
			}
		default:
			b.WriteByte(c)
		}
		i++
	}
	return "", 0, fmt.Errorf("unterminated string literal")
}

// classify 按简报规则分类:全大写/下划线的字面镜像归 keywords(出现顺序);
// 白名单内的标点镜像归 operators(白名单顺序,去重);白名单条目必须
// 全部在 tokenImage 中出现,否则说明语法与白名单漂移,报错。
func classify(images []string) (keywords, operators []string, err error) {
	keywords = make([]string, 0, len(images))
	seen := make(map[string]bool, len(operatorWhitelist))
	for _, img := range images {
		if len(img) < 2 || img[0] != '"' || img[len(img)-1] != '"' {
			continue
		}
		inner := img[1 : len(img)-1]
		switch {
		case keywordRe.MatchString(inner):
			keywords = append(keywords, inner)
		case seen[inner]:
			// 重复字面镜像(理论上不应出现),忽略
		default:
			for _, op := range operatorWhitelist {
				if op == inner {
					seen[inner] = true
					break
				}
			}
		}
	}
	operators = make([]string, 0, len(operatorWhitelist))
	for _, op := range operatorWhitelist {
		if seen[op] {
			operators = append(operators, op)
		}
	}
	if len(operators) != len(operatorWhitelist) {
		var missing []string
		for _, op := range operatorWhitelist {
			if !seen[op] {
				missing = append(missing, op)
			}
		}
		return nil, nil, fmt.Errorf("operator whitelist items missing from tokenImage: %v", missing)
	}
	return keywords, operators, nil
}
