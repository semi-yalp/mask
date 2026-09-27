// Package config 加载并语义校验 legacy 元数据 YAML（schema 与错误消息对齐
// Java YamlConfigLoader：错误带 YAML 路径、重复键检测、类型词表校验）。
// YAML 语法由 yaml.v3 解析（模块唯一第三方依赖），schema 校验自研走查
// yaml.Node，保证路径级报错与 SnakeYAML 相同的判定边界。
package config

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"

	"io.masklite/go/dialect"
	"io.masklite/go/maskerr"
	"io.masklite/go/metadata"
)

// MaskingPolicy 是一条 UDF 脱敏策略声明。
type MaskingPolicy struct {
	Name      string
	UDF       string
	Arguments []any // string | bool | int64 | float64
}

// ColumnBinding 是一列到策略的绑定。
type ColumnBinding struct {
	Key          metadata.ColumnKey // 规范化
	Policy       string
	InheritOnCopy bool
}

// LoadedConfig 是校验后的配置（声明序保留）。
type LoadedConfig struct {
	Source   string
	Tables   []*metadata.Table
	Bindings []ColumnBinding
	Policies []MaskingPolicy
	byName   map[string]int // policy name → Policies 下标
	byTable  map[string]*metadata.Table
}

// FindTable 按规范化名查找表。
func (c *LoadedConfig) FindTable(catalog, schema, name string) *metadata.Table {
	return c.byTable[metadata.TableKey(catalog, schema, name)]
}

// Policy 返回具名策略。
func (c *LoadedConfig) Policy(name string) (MaskingPolicy, bool) {
	i, ok := c.byName[name]
	if !ok {
		return MaskingPolicy{}, false
	}
	return c.Policies[i], true
}

// DeclaredPolicies 按声明序返回策略名（错误消息清单用）。
func (c *LoadedConfig) DeclaredPolicies() []string {
	names := make([]string, 0, len(c.Policies))
	for _, p := range c.Policies {
		names = append(names, p.Name)
	}
	return names
}

// LoadContent 从字符串加载；sourceName 用于错误消息。
func LoadContent(yamlText, sourceName string) (*LoadedConfig, error) {
	return LoadContentWithDialect(yamlText, sourceName, dialect.PostgreSQL.Name)
}

// LoadContentWithDialect 同 LoadContent 但显式方言（非 postgresql 拒绝，
// 对齐 MaskLiteTest 的 dialect 拒绝用例）。
func LoadContentWithDialect(yamlText, sourceName, dialectName string) (*LoadedConfig, error) {
	if _, err := dialect.ByName(dialectName); err != nil {
		return nil, err
	}
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(yamlText), &root); err != nil {
		return nil, maskerr.Errorf(maskerr.ConfigError, "%s: invalid YAML: %s", sourceName, err)
	}
	doc := documentContent(&root)
	if doc == nil {
		return nil, maskerr.Errorf(maskerr.ConfigError, "%s: root must be a mapping", sourceName)
	}
	if err := checkDuplicateKeys(doc, sourceName); err != nil {
		return nil, err
	}
	l := &loader{source: sourceName}
	cfg, err := l.load(doc)
	if err != nil {
		return nil, err
	}
	return cfg, nil
}

// LoadFile 从文件加载；IO 失败 → IO_ERROR。
func LoadFile(path string) (*LoadedConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, maskerr.Errorf(maskerr.IOError, "cannot read metadata file '%s': %s", path, err)
	}
	return LoadContent(string(data), path)
}

// documentContent 取文档根节点（解开一层 DocumentNode / 别名）。
func documentContent(root *yaml.Node) *yaml.Node {
	n := root
	for n != nil && (n.Kind == yaml.DocumentNode) {
		n = n.Content[0]
	}
	if n == nil {
		return nil
	}
	if n.Kind == yaml.AliasNode && n.Alias != nil {
		n = n.Alias
	}
	return n
}

// checkDuplicateKeys 检测全树映射的重复键（对齐 SnakeYAML
// allowDuplicateKeys=false；Go 侧自扫，消息风格一致）。
func checkDuplicateKeys(n *yaml.Node, source string) error {
	switch n.Kind {
	case yaml.DocumentNode:
		for _, c := range n.Content {
			if err := checkDuplicateKeys(c, source); err != nil {
				return err
			}
		}
	case yaml.SequenceNode:
		for _, c := range n.Content {
			if err := checkDuplicateKeys(c, source); err != nil {
				return err
			}
		}
	case yaml.MappingNode:
		seen := map[string]int{}
		for i := 0; i+1 < len(n.Content); i += 2 {
			k := n.Content[i]
			if prev, dup := seen[k.Value]; dup {
				return maskerr.Errorf(maskerr.ConfigError,
					"%s: invalid YAML: mapping key \"%s\" already defined at line %d",
					source, k.Value, prev)
			}
			seen[k.Value] = k.Line
			if err := checkDuplicateKeys(n.Content[i+1], source); err != nil {
				return err
			}
		}
	case yaml.AliasNode:
		if n.Alias != nil {
			return checkDuplicateKeys(n.Alias, source)
		}
	}
	return nil
}

type loader struct {
	source string
}

func (l *loader) path(suffix string) string {
	return l.source + ": " + suffix
}

// isString 判断标量是否解析为字符串（对齐 SnakeYAML SafeConstructor 的
// String 判定：tag 必须是 !!str）。
func isString(n *yaml.Node) bool {
	return n.Kind == yaml.ScalarNode && n.Tag == "!!str"
}

// javaTypeName 给非字符串标量一个 Java 类名（rowFilter 类型错误消息用）。
func javaTypeName(n *yaml.Node) string {
	switch n.Tag {
	case "!!int":
		return "Integer"
	case "!!bool":
		return "Boolean"
	case "!!float":
		return "Double"
	case "!!timestamp":
		return "Date"
	case "!!seq":
		return "ArrayList"
	case "!!map":
		return "LinkedHashMap"
	default:
		return n.Tag
	}
}

func (l *loader) requireMapping(n *yaml.Node, message string) error {
	if n == nil || n.Kind != yaml.MappingNode {
		return maskerr.New(maskerr.ConfigError, message)
	}
	return nil
}

func (l *loader) requireList(n *yaml.Node, message string) error {
	if n == nil || n.Kind != yaml.SequenceNode {
		return maskerr.New(maskerr.ConfigError, message)
	}
	return nil
}

// mapGet 从映射取键（别名已解开）；ok=false 表示键不存在或值为显式 null。
func mapGet(m *yaml.Node, key string) (*yaml.Node, bool) {
	if m == nil || m.Kind != yaml.MappingNode {
		return nil, false
	}
	for i := 0; i+1 < len(m.Content); i += 2 {
		k := m.Content[i]
		if k.Value == key {
			v := m.Content[i+1]
			if v.Kind == yaml.AliasNode && v.Alias != nil {
				v = v.Alias
			}
			if v.Kind == yaml.ScalarNode && v.Tag == "!!null" {
				return v, false
			}
			return v, true
		}
	}
	return nil, false
}

func (l *loader) requiredString(m *yaml.Node, key, path string) (string, error) {
	v, ok := mapGet(m, key)
	if !ok || !isString(v) || strings.TrimSpace(v.Value) == "" {
		return "", maskerr.New(maskerr.ConfigError,
			path+"."+key+": required non-blank string is missing")
	}
	return v.Value, nil
}

func (l *loader) load(root *yaml.Node) (*LoadedConfig, error) {
	if err := l.requireMapping(root, l.path("root must be a mapping")); err != nil {
		return nil, err
	}
	cfg := &LoadedConfig{Source: l.source, byName: map[string]int{}, byTable: map[string]*metadata.Table{}}

	if err := l.loadTables(root, cfg); err != nil {
		return nil, err
	}
	if err := l.loadPolicies(root, cfg); err != nil {
		return nil, err
	}
	if err := l.loadColumnBindings(root, cfg); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (l *loader) loadTables(root *yaml.Node, cfg *LoadedConfig) error {
	metadataNode, ok := mapGet(root, "metadata")
	if !ok {
		return maskerr.New(maskerr.ConfigError, l.path("'metadata' must be a mapping"))
	}
	if err := l.requireMapping(metadataNode, l.path("'metadata' must be a mapping")); err != nil {
		return err
	}
	tablesNode, ok := mapGet(metadataNode, "tables")
	if !ok {
		return maskerr.New(maskerr.ConfigError, l.path("'metadata.tables' must be a list"))
	}
	if err := l.requireList(tablesNode, l.path("'metadata.tables' must be a list")); err != nil {
		return err
	}
	seenTables := map[string]bool{}
	for i, tableNode := range tablesNode.Content {
		tablePath := fmt.Sprintf("metadata.tables[%d]", i)
		if err := l.requireMapping(tableNode, l.path(tablePath+" must be a mapping")); err != nil {
			return err
		}
		catalog, err := l.requiredString(tableNode, "catalog", tablePath)
		if err != nil {
			return err
		}
		schema, err := l.requiredString(tableNode, "schema", tablePath)
		if err != nil {
			return err
		}
		name, err := l.requiredString(tableNode, "name", tablePath)
		if err != nil {
			return err
		}
		rowFilter := ""
		if rf, exists := mapGet(tableNode, "rowFilter"); exists {
			if !isString(rf) {
				return maskerr.New(maskerr.ConfigError,
					l.path(tablePath+".rowFilter must be a string, but was "+javaTypeName(rf)))
			}
			rowFilter = rf.Value
		}
		tableKey := metadata.TableKey(catalog, schema, name)
		if seenTables[tableKey] {
			return maskerr.New(maskerr.ConfigError,
				l.path(tablePath+": duplicate table '"+catalog+"."+schema+"."+name+"'"))
		}
		seenTables[tableKey] = true
		columnsNode, ok := mapGet(tableNode, "columns")
		if !ok {
			return maskerr.New(maskerr.ConfigError, l.path(tablePath+".columns must be a list"))
		}
		if err := l.requireList(columnsNode, l.path(tablePath+".columns must be a list")); err != nil {
			return err
		}
		if len(columnsNode.Content) == 0 {
			return maskerr.New(maskerr.ConfigError,
				l.path(tablePath+".columns must declare at least one column"))
		}
		seenColumns := map[string]bool{}
		var columns []metadata.Column
		for j, columnNode := range columnsNode.Content {
			columnPath := fmt.Sprintf("%s.columns[%d]", tablePath, j)
			if err := l.requireMapping(columnNode, l.path(columnPath+" must be a mapping")); err != nil {
				return err
			}
			columnName, err := l.requiredString(columnNode, "name", columnPath)
			if err != nil {
				return err
			}
			columnType, err := l.requiredString(columnNode, "type", columnPath)
			if err != nil {
				return err
			}
			normalized := metadata.Normalize(columnName)
			if seenColumns[normalized] {
				return maskerr.New(maskerr.ConfigError,
					l.path(columnPath+": duplicate column name '"+columnName+"'"))
			}
			seenColumns[normalized] = true
			col, err := metadata.ParseColumn(columnName, columnType)
			if err != nil {
				return maskerr.New(maskerr.ConfigError, l.path(columnPath+".type: "+err.(*maskerr.Error).Message))
			}
			columns = append(columns, col)
		}
		t := &metadata.Table{Catalog: catalog, Schema: schema, Name: name,
			Columns: columns, RowFilter: strings.TrimSpace(rowFilter)}
		if strings.TrimSpace(rowFilter) == "" {
			t.RowFilter = ""
		}
		cfg.Tables = append(cfg.Tables, t)
		cfg.byTable[tableKey] = t
	}
	return nil
}

func (l *loader) loadPolicies(root *yaml.Node, cfg *LoadedConfig) error {
	policiesNode, ok := mapGet(root, "policies")
	if !ok {
		return maskerr.New(maskerr.ConfigError, l.path("'policies' must be a mapping"))
	}
	if err := l.requireMapping(policiesNode, l.path("'policies' must be a mapping")); err != nil {
		return err
	}
	for i := 0; i+1 < len(policiesNode.Content); i += 2 {
		key := policiesNode.Content[i]
		value := policiesNode.Content[i+1]
		if value.Kind == yaml.AliasNode && value.Alias != nil {
			value = value.Alias
		}
		if key.Tag != "!!str" {
			return maskerr.New(maskerr.ConfigError, l.path("policies: policy names must be strings"))
		}
		policyPath := "policies." + key.Value
		if err := l.requireMapping(value, l.path(policyPath+" must be a mapping")); err != nil {
			return err
		}
		udf, err := l.requiredString(value, "udf", policyPath)
		if err != nil {
			return err
		}
		var arguments []any
		if argsNode, exists := mapGet(value, "arguments"); exists {
			if err := l.requireList(argsNode, l.path(policyPath+".arguments must be a list")); err != nil {
				return err
			}
			arguments, err = l.policyArguments(argsNode, key.Value, policyPath)
			if err != nil {
				return err
			}
		}
		cfg.Policies = append(cfg.Policies, MaskingPolicy{Name: key.Value, UDF: udf, Arguments: arguments})
		cfg.byName[key.Value] = len(cfg.Policies) - 1
	}
	return nil
}

// policyArguments 标量参数转换；null/map/list 一律拒绝（对齐
// MaskingPolicy.validateArguments）。
func (l *loader) policyArguments(argsNode *yaml.Node, policyName, policyPath string) ([]any, error) {
	out := make([]any, 0, len(argsNode.Content))
	for _, a := range argsNode.Content {
		if a.Kind == yaml.AliasNode && a.Alias != nil {
			a = a.Alias
		}
		switch {
		case a.Kind != yaml.ScalarNode:
			return nil, maskerr.New(maskerr.ConfigError,
				l.path(policyPath+": policy '"+policyName+"' argument '<"+javaTypeName(a)+">' must be a scalar (string, number or boolean)"))
		case a.Tag == "!!null":
			return nil, maskerr.New(maskerr.ConfigError,
				l.path(policyPath+": policy '"+policyName+"' argument 'null' must be a scalar (string, number or boolean)"))
		case a.Tag == "!!str":
			out = append(out, a.Value)
		case a.Tag == "!!bool":
			out = append(out, strings.EqualFold(a.Value, "true"))
		case a.Tag == "!!int":
			var v int64
			fmt.Sscanf(a.Value, "%d", &v)
			out = append(out, v)
		case a.Tag == "!!float":
			var v float64
			fmt.Sscanf(a.Value, "%g", &v)
			out = append(out, v)
		default:
			return nil, maskerr.New(maskerr.ConfigError,
				l.path(policyPath+": policy '"+policyName+"' argument '"+a.Value+"' must be a scalar (string, number or boolean)"))
		}
	}
	return out, nil
}

func (l *loader) loadColumnBindings(root *yaml.Node, cfg *LoadedConfig) error {
	columnsNode, ok := mapGet(root, "columns")
	if !ok {
		return nil
	}
	if err := l.requireList(columnsNode, l.path("'columns' must be a list")); err != nil {
		return err
	}
	seen := map[string]bool{}
	for i, bindingNode := range columnsNode.Content {
		bindingPath := fmt.Sprintf("columns[%d]", i)
		if err := l.requireMapping(bindingNode, l.path(bindingPath+" must be a mapping")); err != nil {
			return err
		}
		catalog, err := l.requiredString(bindingNode, "catalog", bindingPath)
		if err != nil {
			return err
		}
		schema, err := l.requiredString(bindingNode, "schema", bindingPath)
		if err != nil {
			return err
		}
		table, err := l.requiredString(bindingNode, "table", bindingPath)
		if err != nil {
			return err
		}
		column, err := l.requiredString(bindingNode, "column", bindingPath)
		if err != nil {
			return err
		}
		policyName, err := l.requiredString(bindingNode, "policy", bindingPath)
		if err != nil {
			return err
		}
		if _, declared := cfg.byName[policyName]; !declared {
			return maskerr.New(maskerr.ConfigError,
				l.path(bindingPath+".policy: unknown policy '"+policyName+"' (declared policies: "+
					"["+strings.Join(cfg.DeclaredPolicies(), ", ")+"])"))
		}
		key := metadata.Key(catalog, schema, table, column)
		if seen[key.String()] {
			return maskerr.New(maskerr.ConfigError,
				l.path(bindingPath+": duplicate policy binding for column '"+key.String()+"'"))
		}
		seen[key.String()] = true
		inherit := false
		if inheritNode, exists := mapGet(bindingNode, "inheritOnCopy"); exists {
			if inheritNode.Tag != "!!bool" {
				return maskerr.New(maskerr.ConfigError,
					l.path(bindingPath+".inheritOnCopy must be boolean"))
			}
			inherit = strings.EqualFold(inheritNode.Value, "true")
		}
		cfg.Bindings = append(cfg.Bindings, ColumnBinding{Key: key, Policy: policyName, InheritOnCopy: inherit})
	}
	return nil
}
