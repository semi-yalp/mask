package contract

import (
	"errors"

	"io.sqlmask/go/dialect"
	"io.sqlmask/go/engine"
	"io.sqlmask/go/maskerr"
	"io.sqlmask/go/parser"
	"io.sqlmask/go/split"
)

// Verdict 是解析期判定:Stage ∈ parse|classify|accept(Go 侧)与 later
// (仅 Java 侧——M2+ 才判定的失败码,M1 视为解析接受);Code 记录错误码
// (accept/later 形态下可为空)。契约只比 Stage(+parse/classify 的 Code)。
type Verdict struct {
	Stage string
	Code  string
}

// 判定阶段常量。
const (
	StageParse    = "parse"
	StageClassify = "classify"
	StageAccept   = "accept"
	StageLater    = "later" // 仅出现在 Java 侧契约记录中
)

// GoVerdict 对单条语句按 Go 管线给出解析期判定:split → lexer/parser
// (parser.New+ParseStatement)→ engine.Classify。错误归类:
//   - maskerr.PARSE_ERROR(含 lexer 错误)→ parse;
//   - maskerr.UNSUPPORTED_STATEMENT → classify——两类来源同归此阶段:
//     engine.Classify 的拒绝,与 parser.ParseStatement 首词 12 词分路的
//     UNSUPPORTED_STATEMENT(T9 裁定:镜像 Java classify 拒绝,错误码语义
//     归 classify 阶段);
//   - 其余码(正常管线不会出现)→ parse 阶段 + 原码,差异会在比对中显形;
//   - panic(harness 防 Go 侧 bug 崩溃)→ parse 阶段 + "PANIC",同样显形。
//
// 输入按 split.Statements 逐条判定并取首个非 accept(镜像 Java 管线对多
// 语句输入 fail-fast);契约语料已按语句切分,正常退化为单条。
func GoVerdict(sql, dialectName string) (v Verdict) {
	// panic 兜底:parser/engine 的未预期 panic 转成 parse 拒绝,防 bug 崩
	// harness;差异会在 diff 测试中显形(Java 侧不会是 PANIC)。
	defer func() {
		if r := recover(); r != nil {
			v = Verdict{Stage: StageParse, Code: "PANIC"}
		}
	}()
	prof, err := dialect.ByName(dialectName)
	if err != nil {
		panic("contract: GoVerdict: " + err.Error())
	}
	for _, stmt := range split.Statements(sql) {
		if got := goVerdictOne(prof, stmt); got.Stage != StageAccept {
			return got
		}
	}
	return Verdict{Stage: StageAccept}
}

// goVerdictOne 对切分后的单条语句走 lexer/parser/classify 管线。
func goVerdictOne(prof *dialect.Profile, stmt string) Verdict {
	p := parser.New(prof, stmt)
	st, err := p.ParseStatement()
	if err != nil {
		return verdictFromErr(err)
	}
	if err := engine.Classify(st, 0); err != nil {
		return verdictFromErr(err)
	}
	return Verdict{Stage: StageAccept}
}

// verdictFromErr 把管线错误折算成解析期判定(见 GoVerdict 注释的归类规则)。
func verdictFromErr(err error) Verdict {
	var me *maskerr.Error
	if !errors.As(err, &me) {
		return Verdict{Stage: StageParse, Code: "ERROR"}
	}
	switch me.Code {
	case maskerr.UnsupportedStatement:
		return Verdict{Stage: StageClassify, Code: "UNSUPPORTED_STATEMENT"}
	case maskerr.ParseError:
		return Verdict{Stage: StageParse, Code: "PARSE_ERROR"}
	default:
		return Verdict{Stage: StageParse, Code: string(me.Code)}
	}
}

// Compare 对全语料逐条比对 GoVerdict 与记录的 Java 判定(M1 绿判定):
// Java later ≡ Go accept;parse/classify 阶段还需 Code 相等。缺 key 记为
// mismatch(Java 侧 Stage "missing")。返回空切片即 M1 绿。
func Compare(corpus []Case, java map[string]Verdict) []Mismatch {
	var out []Mismatch
	for _, c := range corpus {
		jv, ok := java[Key(c)]
		if !ok {
			jv = Verdict{Stage: "missing"}
		}
		gv := GoVerdict(c.SQL, c.Dialect)
		if !equivalent(jv, gv) {
			out = append(out, Mismatch{Case: c, Java: jv, Go: gv})
		}
	}
	return out
}

// equivalent 报告两个判定在 M1 语义下是否相等:Java later 折算为 accept
// (Code 一并清空);accept 不比 Code;parse/classify 比 Stage+Code。
func equivalent(java, got Verdict) bool {
	if java.Stage == StageLater {
		java = Verdict{Stage: StageAccept}
	}
	if java.Stage != got.Stage {
		return false
	}
	if java.Stage == StageAccept {
		return true
	}
	return java.Code == got.Code
}

// Mismatch 记录一条比对失败:语料条目与两侧判定。
type Mismatch struct {
	Case Case
	Java Verdict
	Go   Verdict
}
