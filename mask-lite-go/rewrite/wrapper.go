package rewrite

import (
	"regexp"
	"strconv"
	"strings"

	"io.masklite/go/maskerr"
	"io.masklite/go/policy"
	"io.masklite/go/render"
)

// wrapperAlias 是包装派生表别名（对齐 Java WRAPPER_ALIAS）。
const wrapperAlias = "r"

// syntheticName 是 Calcite 为未命名投影项推导的名字。
var syntheticName = regexp.MustCompile(`^EXPR\$\d+$`)

// generatedPrefix 是合成列改名前缀。
const generatedPrefix = "mask_col_"

// ensureWrapperIsSafe 包装前安全检查：重复输出名（大小写不敏感）无法被
// 外层无歧义引用 → REWRITE_ERROR（PG 支持别名清单，合成列可直接改名）。
func ensureWrapperIsSafe(plan []outPlan) error {
	seen := map[string]bool{}
	for _, o := range plan {
		lower := strings.ToLower(o.name)
		if seen[lower] {
			return maskerr.Errorf(maskerr.RewriteError,
				"cannot wrap a query whose output contains duplicate column name '%s': the wrapper could not reference the right column "+
					"unambiguously in dialect 'postgresql'", o.name)
		}
		seen[lower] = true
	}
	return nil
}

// buildWrapper 生成外层包装（模板与 Java SqlRewriteService/buildWrapper
// 及实测输出逐字对齐）：
//
//	SELECT udf(r.col, args…) AS col, r.col2, … FROM (
//	<inner>
//	) AS r[ (别名清单)]
func buildWrapper(innerSQL string, plan []outPlan) (string, error) {
	final := make([]string, len(plan))
	generated := 0
	renamed := false
	for i, o := range plan {
		name := o.name
		if syntheticName.MatchString(name) {
			generated++
			name = generatedPrefix + strconv.Itoa(generated)
		}
		final[i] = name
		if name != o.name {
			renamed = true
		}
	}
	items := make([]string, 0, len(plan))
	for i, o := range plan {
		ref := wrapperAlias + "." + render.Ident(final[i])
		if o.masked {
			call, err := renderUdfCall(o.inst, ref)
			if err != nil {
				return "", err
			}
			items = append(items, call+" AS "+render.Ident(final[i]))
		} else {
			items = append(items, ref)
		}
	}
	var b strings.Builder
	b.WriteString("SELECT ")
	b.WriteString(strings.Join(items, ", "))
	b.WriteString(" FROM (\n")
	b.WriteString(innerSQL)
	b.WriteString("\n) AS ")
	b.WriteString(render.Ident(wrapperAlias))
	if renamed {
		aliases := make([]string, 0, len(final))
		for _, name := range final {
			aliases = append(aliases, render.Ident(name))
		}
		b.WriteString(" (")
		b.WriteString(strings.Join(aliases, ", "))
		b.WriteString(")")
	}
	return b.String(), nil
}

// renderUdfCall 渲染 udf(reference, arg1, …)——参数走字面量渲染，绝不拼接
// 原始值（防注入，对齐 Java renderLiteral）。
func renderUdfCall(inst policy.Instruction, reference string) (string, error) {
	args := make([]string, 0, len(inst.Arguments)+1)
	args = append(args, reference)
	for _, a := range inst.Arguments {
		s, err := renderScalar(a, inst)
		if err != nil {
			return "", err
		}
		args = append(args, s)
	}
	return render.Ident(inst.UDF) + "(" + strings.Join(args, ", ") + ")", nil
}

// renderScalar 对齐 SqlRewriteService.toLiteral 的类型分派：
// bool → TRUE/FALSE；整数 → 精确十进制；浮点 → 近似数；字符串 → '…'。
func renderScalar(v any, inst policy.Instruction) (string, error) {
	switch x := v.(type) {
	case bool:
		if x {
			return "TRUE", nil
		}
		return "FALSE", nil
	case int64:
		return strconv.FormatInt(x, 10), nil
	case int:
		return strconv.Itoa(x), nil
	case float64:
		// BigDecimal.valueOf(d).toPlainString() 的 Go 等价：最短十进制
		// 表示（无指数）
		return strconv.FormatFloat(x, 'f', -1, 64), nil
	case string:
		return render.QuoteString(x), nil
	default:
		return "", maskerr.Errorf(maskerr.ConfigError,
			"policy '%s' has an argument of unsupported type: %v", inst.PolicyName, x)
	}
}
