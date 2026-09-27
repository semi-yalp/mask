// Package masklite 是脱敏+行过滤改写内核的最简嵌入门面（Go 版），
// PostgreSQL 方言：配置是 legacy 元数据 YAML（表结构 + columns 列策略
// 绑定 + rowFilter 行过滤 + policies UDF 声明），策略对所有人无条件生效；
// 输入与输出同为 PostgreSQL 方言。引擎只做解析、校验、血缘分析与 SQL
// 输出，从不连接数据库。
package masklite

import (
	"io.masklite/go/config"
	"io.masklite/go/rewrite"
)

// StatementRewrite 是单条语句的改写结果。
type StatementRewrite = rewrite.StatementRewrite

// MaskLite 是门面。
type MaskLite struct {
	loaded *config.LoadedConfig
	engine *rewrite.Engine
}

// FromYaml 加载并校验 YAML 配置内容（sourceName 固定 "metadata.yaml"）。
func FromYaml(metadataYaml string) (*MaskLite, error) {
	loaded, err := config.LoadContent(metadataYaml, "metadata.yaml")
	if err != nil {
		return nil, err
	}
	return newMaskLite(loaded)
}

// FromYamlFile 加载并校验 YAML 配置文件。
func FromYamlFile(path string) (*MaskLite, error) {
	loaded, err := config.LoadFile(path)
	if err != nil {
		return nil, err
	}
	return newMaskLite(loaded)
}

func newMaskLite(loaded *config.LoadedConfig) (*MaskLite, error) {
	engine, err := rewrite.New(loaded)
	if err != nil {
		return nil, err
	}
	return &MaskLite{loaded: loaded, engine: engine}, nil
}

// RewriteStatements 逐语句改写；任一失败整体失败（无部分结果）。
func (m *MaskLite) RewriteStatements(sqlText string) ([]StatementRewrite, error) {
	return m.engine.Rewrite(sqlText)
}

// Rewrite 改写并拼为脚本：每语句尾分号，语句间空行；空输入返回空串。
func (m *MaskLite) Rewrite(sqlText string) (string, error) {
	statements, err := m.engine.Rewrite(sqlText)
	if err != nil {
		return "", err
	}
	return rewrite.Join(statements), nil
}

// Config 返回已加载配置（只读视图）。
func (m *MaskLite) Config() *config.LoadedConfig { return m.loaded }
