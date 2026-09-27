package parser

import (
	"strings"
	"testing"

	"io.masklite/go/dialect"
)

// dialect4test 返回测试方言。
func dialect4test() *dialect.Profile { return dialect.PostgreSQL }

// intervalYAML 对齐 PgBareIntervalTest 的配置（date_dim 表）。
const intervalYAML = "unused"

// interval 渲染断言（解析 + 渲染，golden 对齐 PgBareIntervalTest 期望）。
func TestBareIntervalNormalization(t *testing.T) {
	tests := []struct {
		name string
		sql  string
		want string
	}{
		{"裸天", "SELECT 1 + INTERVAL '1 day'", "INTERVAL '1' DAY"},
		{"裸小时", "SELECT 1 + INTERVAL '2 hours'", "INTERVAL '0 02:00:00' DAY TO SECOND"},
		{"时与分累加", "SELECT 1 + INTERVAL '2 hours 30 minutes'", "INTERVAL '0 02:30:00' DAY TO SECOND"},
		{"年月", "SELECT 1 + INTERVAL '1 year 2 mons'", "INTERVAL '1-2' YEAR TO MONTH"},
		{"裸月", "SELECT 1 + INTERVAL '6 months'", "INTERVAL '6' MONTH"},
		{"裸年", "SELECT 1 + INTERVAL '2 years'", "INTERVAL '2' YEAR"},
		{"天数+时钟", "SELECT 1 + INTERVAL '1 02:03:04'", "INTERVAL '1 02:03:04' DAY TO SECOND"},
		{"纯时钟", "SELECT 1 + INTERVAL '02:03'", "INTERVAL '0 02:03:00' DAY TO SECOND"},
		{"分数秒", "SELECT 1 + INTERVAL '1.5 seconds'", "INTERVAL '0 00:00:01.500000' DAY TO SECOND"},
		{"毫秒", "SELECT 1 + INTERVAL '500 milliseconds'", "INTERVAL '0 00:00:00.500000' DAY TO SECOND"},
		{"串内负号", "SELECT 1 + INTERVAL '-1 day'", "INTERVAL '-1' DAY"},
		{"混合符号净额", "SELECT 1 + INTERVAL '1 day -2 hours'", "INTERVAL '0 22:00:00' DAY TO SECOND"},
		{"周折算", "SELECT 1 + INTERVAL '2 weeks'", "INTERVAL '14' DAY"},
		{"前导 2 位边界", "SELECT 1 + INTERVAL '99 days'", "INTERVAL '99' DAY"},
		{"前导 2 位年", "SELECT 1 + INTERVAL '99 years'", "INTERVAL '99' YEAR"},
		{"月折算年月", "SELECT 1 + INTERVAL '400 months'", "INTERVAL '33-4' YEAR TO MONTH"},
		{"::interval 字符串", "SELECT 1 + '1 day'::interval", "INTERVAL '1' DAY"},
		{"::interval 小时", "SELECT 1 + '2 hours'::interval", "INTERVAL '0 02:00:00' DAY TO SECOND"},
		{"CAST AS INTERVAL", "SELECT 1 + CAST('1 year 2 mons' AS INTERVAL)", "INTERVAL '1-2' YEAR TO MONTH"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parseRender(t, tt.sql)
			if !strings.Contains(got, tt.want) {
				t.Fatalf("got %q, want contains %q", got, tt.want)
			}
		})
	}
}

func TestIntervalRejections(t *testing.T) {
	for _, sql := range []string{
		"SELECT 1 + INTERVAL '400 days'",   // 前导 >2 位
		"SELECT 1 + INTERVAL '100 days'",   // 边界外
		"SELECT 1 + INTERVAL '100 years'",  // 边界外
		"SELECT 1 + INTERVAL '1200 months'", // 折算后前导 100 >2 位
		"SELECT 1 + INTERVAL '1 year 1 day'", // 跨族混合
		"SELECT 1 + INTERVAL '1.5 months'",   // 分数月
		"SELECT 1 + INTERVAL 'fortnight'",    // 未知单位
		"SELECT 1 + INTERVAL ''",             // 空
		"SELECT 1 + INTERVAL '1 fortnights'", // 未知单位
		"SELECT 1 + INTERVAL '@ 1 day'",      // legacy 装饰
		"SELECT 1 + INTERVAL '1 day ago'",    // legacy 装饰
		"SELECT 1 + '1 day'::interval(3)",    // typmod
		"SELECT 1 + '1 day'::interval day",   // 字段范围
		"SELECT 1 + ('1'||' day')::interval", // 非字符串字面量
		"SELECT 1 + CAST(1 AS INTERVAL)",     // 非字符串字面量
	} {
		p, err := New(dialect4test(), sql)
		if err != nil {
			continue
		}
		if _, err := p.ParseStatement(); err == nil {
			t.Fatalf("want parse error for %q", sql)
		}
	}
}

// 限定词形式（带单位）不走规范化，符号作用于值。
func TestQualifiedIntervalSign(t *testing.T) {
	got := parseRender(t, "SELECT 1 + INTERVAL -'3' DAY")
	if !strings.Contains(got, "INTERVAL '-3' DAY") {
		t.Fatalf("qualified sign: %q", got)
	}
}
