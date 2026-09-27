// Package dialect 定义 mask-lite-go 的方言配置。内核只支持 PostgreSQL；
// 任何其他方言名在加载期即被拒绝（CONFIG_ERROR，消息与 Java
// DialectProfiles.byName 一致）。
package dialect

import (
	"strings"

	"io.masklite/go/maskerr"
)

// Casing 决定未引号/引号标识符在解析期的大小写折算。
type Casing int

const (
	// ToLower 把未引号标识符折算为小写（PG 惯例）。
	ToLower Casing = iota
	// Unchanged 保持原样。
	Unchanged
)

// Profile 是解析与名字匹配所需的方言配置。
type Profile struct {
	Name string
	// UnquotedCasing / QuotedCasing：解析期标识符折算。
	UnquotedCasing Casing
	QuotedCasing   Casing
	// CaseSensitiveNameMatching：名字比较是否大小写敏感。
	// PG 解析期已把未引号标识符折小写，因此 true 意味着"带大写的引用视为
	// 带引号，必须与声明逐字符一致"。
	CaseSensitiveNameMatching bool
}

// PostgreSQL 是唯一受支持的方言。
var PostgreSQL = &Profile{
	Name:                      "postgresql",
	UnquotedCasing:            ToLower,
	QuotedCasing:              Unchanged,
	CaseSensitiveNameMatching: true,
}

// ByName 按名字取方言；非 postgresql（大小写不敏感比较）一律 CONFIG_ERROR。
func ByName(name string) (*Profile, error) {
	if strings.EqualFold(name, PostgreSQL.Name) {
		return PostgreSQL, nil
	}
	return nil, maskerr.Errorf(maskerr.ConfigError,
		"unsupported dialect '%s'; mask-lite only supports: postgresql", name)
}
