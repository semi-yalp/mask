// Package policy 是策略决策点（PDP）：legacy 路径的列绑定 → 掩码决策。
// legacy 策略对所有人无条件生效、priority 恒 0，因此决策即"规范化列键
// 精确匹配绑定"；多来源 tie-break 对齐 PdpMaskSelector——命中决策的
// origins 中规范化列键字典序最小者胜（首现胜平局）。
package policy

import (
	"io.masklite/go/config"
	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
)

// Instruction 是一条掩码指令（UDF + 渲染参数）。
type Instruction struct {
	PolicyName string
	UDF        string
	Arguments  []any
}

// Engine 是无状态决策器。
type Engine struct {
	bindings map[metadata.ColumnKey]ColumnDecision
}

// ColumnDecision 是一列的绑定决策。
type ColumnDecision struct {
	Policy   config.MaskingPolicy
	InheritOnCopy bool
}

// NewEngine 从已加载配置构建（legacy 绑定键在加载期已保证唯一）。
func NewEngine(cfg *config.LoadedConfig) *Engine {
	e := &Engine{bindings: map[metadata.ColumnKey]ColumnDecision{}}
	for _, b := range cfg.Bindings {
		p, ok := cfg.Policy(b.Policy)
		if !ok {
			continue
		}
		e.bindings[b.Key] = ColumnDecision{Policy: p, InheritOnCopy: b.InheritOnCopy}
	}
	return e
}

// MaskFor 决策一列的掩码指令；无命中返回 false。
func (e *Engine) MaskFor(catalog, schema, table, column string) (Instruction, bool) {
	d, ok := e.bindings[metadata.Key(catalog, schema, table, column)]
	if !ok {
		return Instruction{}, false
	}
	return Instruction{PolicyName: d.Policy.Name, UDF: d.Policy.UDF, Arguments: d.Policy.Arguments}, true
}

// SelectMask 对齐 PdpMaskSelector：origins 中有决策的键取字典序最小
// （首现胜平局）。
func SelectMask(origins []metadata.ColumnKey, e *Engine) (Instruction, bool) {
	var bestKey *metadata.ColumnKey
	var best Instruction
	for _, key := range origins {
		inst, ok := e.MaskFor(key.Catalog, key.Schema, key.Table, key.Column)
		if !ok {
			continue
		}
		if bestKey == nil || metadata.CompareKeys(key, *bestKey) < 0 {
			k := key
			bestKey = &k
			best = inst
		}
	}
	return best, bestKey != nil
}

// RenderArguments 校验参数可渲染性（加载期已过滤标量；此函数兜底
// 未知类型 → CONFIG_ERROR，消息对齐 SqlRewriteService.toLiteral）。
func RenderArguments(inst Instruction) error {
	for _, a := range inst.Arguments {
		switch a.(type) {
		case string, bool, int64, float64:
		default:
			return maskerr.Errorf(maskerr.ConfigError,
				"policy '%s' has an argument of unsupported type: %v", inst.PolicyName, a)
		}
	}
	return nil
}
