// Package rewrite 编排整条改写管线（对齐 Java RewriteEngine）：
// 拆分 → 解析 → 只读门 → 行过滤注入（校验/血缘之前）→ 渲染内层 →
// CTE 内联 + 血缘 → 掩码计划 → 包装器输出。任一语句失败整体失败。
package rewrite

import (
	"strings"

	"io.masklite/go/ast"
	"io.masklite/go/config"
	"io.masklite/go/dialect"
	"io.masklite/go/lineage"
	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
	"io.masklite/go/parser"
	"io.masklite/go/policy"
	"io.masklite/go/render"
	"io.masklite/go/rowfilter"
	"io.masklite/go/split"
)

// StatementRewrite 是单条语句的改写结果（对齐 Java record）。
type StatementRewrite struct {
	Ordinal      int
	OriginalSQL  string
	RewrittenSQL string
	Masked       bool
	RowFiltered  bool
}

// Engine 是可复用的改写引擎。
type Engine struct {
	cfg      *config.LoadedConfig
	registry *rowfilter.Registry
	decider  *policy.Engine
	analyzer *lineage.Analyzer
}

// New 构建引擎；rowFilter 条件在此校验（任何语句之前，CONFIG_ERROR 无
// ordinal 前缀）。
func New(cfg *config.LoadedConfig) (*Engine, error) {
	registry, err := rowfilter.Build(cfg)
	if err != nil {
		return nil, err
	}
	decider := policy.NewEngine(cfg)
	return &Engine{
		cfg:      cfg,
		registry: registry,
		decider:  decider,
		analyzer: lineage.NewAnalyzer(cfg, decider),
	}, nil
}

// Rewrite 逐语句改写；失败即整体失败（无部分结果）。
func (e *Engine) Rewrite(sqlText string) ([]StatementRewrite, error) {
	statements := split.Statements(sqlText)
	out := make([]StatementRewrite, 0, len(statements))
	for i, stmtText := range statements {
		ordinal := i + 1
		r, err := e.rewriteOne(stmtText, ordinal)
		if err != nil {
			return nil, prefixStatement(err, ordinal)
		}
		out = append(out, r)
	}
	return out, nil
}

// prefixStatement 对齐 Java 的 ordinal 前缀规则：parse 错误自带前缀不再补。
func prefixStatement(err error, ordinal int) error {
	me, ok := err.(*maskerr.Error)
	if !ok {
		return err
	}
	if strings.HasPrefix(me.Message, "statement ") {
		return me
	}
	return &maskerr.Error{Code: me.Code, Message: "statement " + itoa(ordinal) + ": " + me.Message, Err: me}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

type outPlan struct {
	name   string
	masked bool
	inst   policy.Instruction
}

func (e *Engine) rewriteOne(stmtText string, ordinal int) (StatementRewrite, error) {
	p, err := parser.New(dialect.PostgreSQL, stmtText)
	if err != nil {
		return StatementRewrite{}, maskerr.Errorf(maskerr.ParseError,
			"statement %d: parse error (postgresql): %s", ordinal, err.Error())
	}
	stmt, err := p.ParseStatement()
	if err != nil {
		return StatementRewrite{}, maskerr.Errorf(maskerr.ParseError,
			"statement %d: parse error (postgresql): %s", ordinal, err.Error())
	}
	// 只读门：顶层 kind ∈ {SELECT, ORDER_BY} 之外一律拒绝（裸 WITH 的
	// kind 是 with，与 Java 实证一致）
	if err := gate(stmt, ordinal); err != nil {
		return StatementRewrite{}, err
	}
	// 注入前快照：originalSql 永不含注入
	preSQL := render.Statement(stmt)
	rfw := rowfilter.NewRewriter(e.cfg, e.registry)
	res, err := rfw.Apply(stmt)
	if err != nil {
		return StatementRewrite{}, err
	}
	innerSQL := render.Statement(res.Node)
	expanded, err := lineage.Expand(res.Node)
	if err != nil {
		return StatementRewrite{}, err
	}
	outputs, err := e.analyzer.Analyze(expanded)
	if err != nil {
		return StatementRewrite{}, err
	}
	plan := make([]outPlan, 0, len(outputs))
	requiresWrapper := false
	for _, o := range outputs {
		switch o.Status {
		case lineage.StatusUnknown:
			return StatementRewrite{}, maskerr.Errorf(maskerr.LineageUnknown,
				"output column %d ('%s') has no safely traceable origin; cannot rewrite this statement",
				o.Ordinal, o.Name)
		case lineage.StatusResolved:
			keys := make([]metadata.ColumnKey, 0, len(o.Origins))
			for _, org := range o.Origins {
				keys = append(keys, org.Key)
			}
			inst, hit := policy.SelectMask(keys, e.decider)
			plan = append(plan, outPlan{name: o.Name, masked: hit, inst: inst})
			requiresWrapper = requiresWrapper || hit
		default:
			plan = append(plan, outPlan{name: o.Name})
		}
	}
	rewritten := innerSQL
	if requiresWrapper {
		if err := ensureWrapperIsSafe(plan); err != nil {
			return StatementRewrite{}, err
		}
		rewritten, err = buildWrapper(innerSQL, plan)
		if err != nil {
			return StatementRewrite{}, err
		}
	}
	return StatementRewrite{
		Ordinal:      ordinal,
		OriginalSQL:  preSQL,
		RewrittenSQL: rewritten,
		Masked:       requiresWrapper,
		RowFiltered:  res.Injections > 0,
	}, nil
}

// gate 只读门与 classify 拒绝（两条消息族与 Java 实测一致）。
func gate(stmt ast.Statement, ordinal int) error {
	switch s := stmt.(type) {
	case *ast.Select, *ast.OrderBy:
		return nil
	case *ast.With:
		return gateReject("with")
	case *ast.Unsupported:
		if s.Gate {
			return gateReject(s.KindLowerN)
		}
		return classifyReject(s.KindUpperN, ordinal)
	case *ast.SetOp:
		return classifyReject(s.KindUpper(), ordinal)
	case *ast.Values:
		return classifyReject("VALUES", ordinal)
	default:
		return gateReject("statement")
	}
}

func gateReject(kind string) error {
	return maskerr.Errorf(maskerr.UnsupportedStatement,
		"mask-lite only rewrites SELECT/WITH queries; statement kind '%s' is not supported", kind)
}

func classifyReject(kind string, ordinal int) error {
	return maskerr.Errorf(maskerr.UnsupportedStatement,
		"statement %d: unsupported statement kind %s; only SELECT and WITH ... SELECT queries are supported in this version",
		ordinal, kind)
}

// Join 把逐语句结果拼为脚本（每语句尾分号，语句间空行）。
func Join(statements []StatementRewrite) string {
	parts := make([]string, 0, len(statements))
	for _, s := range statements {
		parts = append(parts, s.RewrittenSQL+";")
	}
	return strings.Join(parts, "\n\n")
}
