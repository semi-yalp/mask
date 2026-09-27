package parser

import (
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"io.sqlmask/go/ast"
	"io.sqlmask/go/dialect"
	"io.sqlmask/go/lexer"
	"io.sqlmask/go/maskerr"
)

// newTestParser 按方言名构造仅持有 Profile 的空 Parser;token 流由
// ParseExprStr 注入(New 的 src 参数路径另有覆盖)。仅测试使用。
func newTestParser(t *testing.T, dialectName string) *Parser {
	t.Helper()
	prof, err := dialect.ByName(dialectName)
	if err != nil {
		t.Fatalf("newTestParser(%q): %v", dialectName, err)
	}
	return &Parser{profile: prof}
}

// ParseExprStr 对 src 重新做词法分析后解析为表达式,供测试书写
// newTestParser(t, "postgresql").ParseExprStr(`...`) 形态的用例。
func (p *Parser) ParseExprStr(src string) (ast.Expr, error) {
	toks, err := lexer.Lex(p.profile, src)
	if err != nil {
		return nil, err
	}
	p.toks, p.cur, p.lexErr = toks, 0, nil
	return p.ParseExpr()
}

// posType 用于识别需要清零的位置字段(全部节点字段拼写为 Pos lexer.Pos)。
var posType = reflect.TypeOf(lexer.Pos{})

// normAST 深度遍历 v,把其中全部 lexer.Pos 字段清零后原样返回,
// 使 reflect.DeepEqual 只比较结构不比较位置。nil 原样返回。
func normAST(v any) any {
	if v != nil {
		zeroPos(reflect.ValueOf(v))
	}
	return v
}

// zeroPos 递归清零 rv 可达的每个 lexer.Pos 字段(rv 须可寻址:
// 测试传入的顶层值均为指针,结构体字段经指针解引后可寻址)。
func zeroPos(rv reflect.Value) {
	switch rv.Kind() {
	case reflect.Pointer, reflect.Interface:
		if !rv.IsNil() {
			zeroPos(rv.Elem())
		}
	case reflect.Struct:
		for i := 0; i < rv.NumField(); i++ {
			f := rv.Field(i)
			if f.Type() == posType {
				f.Set(reflect.Zero(posType))
				continue
			}
			zeroPos(f)
		}
	case reflect.Slice:
		for i := 0; i < rv.Len(); i++ {
			zeroPos(rv.Index(i))
		}
	}
}

// mustExpr 解析成功则返回表达式,失败则令测试致命退出。
func mustExpr(t *testing.T, dialectName, src string) ast.Expr {
	t.Helper()
	got, err := newTestParser(t, dialectName).ParseExprStr(src)
	if err != nil {
		t.Fatalf("ParseExprStr(%q) with dialect %s: unexpected error: %v", src, dialectName, err)
	}
	return got
}

// wantExprError 断言 src 解析失败,错误码为 maskerr.PARSE_ERROR,且 message
// 含 "Line 1, Column wantCol"(wantCol<=0 时只断言含 "Line")。
func wantExprError(t *testing.T, dialectName, src string, wantCol int) {
	t.Helper()
	_, err := newTestParser(t, dialectName).ParseExprStr(src)
	if err == nil {
		t.Fatalf("ParseExprStr(%q) with dialect %s: expected PARSE_ERROR, got nil error", src, dialectName)
	}
	var me *maskerr.Error
	if !errors.As(err, &me) {
		t.Fatalf("ParseExprStr(%q): error %T is not *maskerr.Error: %v", src, err, err)
	}
	if me.Code != maskerr.ParseError {
		t.Fatalf("ParseExprStr(%q): error code = %s, want PARSE_ERROR (message: %s)", src, me.Code, me.Message)
	}
	needle := "Line 1"
	if wantCol > 0 {
		needle = fmt.Sprintf("Line 1, Column %d", wantCol)
	}
	if !strings.Contains(me.Message, needle) {
		t.Fatalf("ParseExprStr(%q): message %q does not contain %q", src, me.Message, needle)
	}
}
