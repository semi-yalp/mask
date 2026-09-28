package contract

import (
	"reflect"
	"testing"

	"io.sqlmask/go/dialect"
	"io.sqlmask/go/engine"
	"io.sqlmask/go/parser"
)

// TestCorpusStatementDistribution M1 收口快照:全语料 110 条中,解析+classify
// 双通过的语句按顶层 Go 类型计数锁定(期望值由一次性统计得出,2026-09-28);
// parse 或 classify 拒绝合计 24 条——两类拒绝都是契约的合法判定,逐条对齐
// 由 Recorded 差分测试守护,本快照只锁总量。AST 结构变化(如新增/删除语句
// 形态)会在此显形,提示确认后更新快照而非静默漂移。
func TestCorpusStatementDistribution(t *testing.T) {
	want := map[string]int{
		"CreateTable":     5,
		"Insert":          6,
		"InsertOverwrite": 4,
		"OrderBy":         56,
		"Select":          11,
		"With":            4,
	}
	got := map[string]int{}
	rejected := 0
	for _, c := range LoadCorpus() {
		prof, err := dialect.ByName(c.Dialect)
		if err != nil {
			t.Fatalf("%s#%d: %v", c.File, c.Ordinal, err)
		}
		stmt, err := parser.New(prof, c.SQL).ParseStatement()
		if err != nil {
			rejected++
			continue
		}
		if err := engine.Classify(stmt, c.Ordinal); err != nil {
			rejected++
			continue
		}
		got[reflect.TypeOf(stmt).Elem().Name()]++
	}
	total := 0
	for k, n := range got {
		total += n
		if want[k] != n {
			t.Errorf("type %s count = %d, want %d (AST/语料结构变化——确认后更新本快照)", k, n, want[k])
		}
	}
	wantTotal := 0
	for _, n := range want {
		wantTotal += n
	}
	if total != wantTotal {
		t.Errorf("accepted total = %d, want %d", total, wantTotal)
	}
	if rejected != 24 {
		t.Errorf("rejected (parse+classify) count = %d, want 24 (其余判定由契约 Recorded 测试守护)", rejected)
	}
}
