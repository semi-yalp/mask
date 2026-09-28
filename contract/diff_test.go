package contract

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"io.sqlmask/go/internal/repotool"
)

// recordedPath 返回提交进仓库的契约文件路径(testdata/contract/
// parse-verdicts.json,仓库根相对;测试 cwd 是本包目录)。
func recordedPath(t *testing.T) string {
	t.Helper()
	return filepath.Join("..", "testdata", "contract", "parse-verdicts.json")
}

// loadRecords 读入并解码契约 json;重复 key 视为契约文件损坏。
func loadRecords(t *testing.T) []Record {
	t.Helper()
	data, err := os.ReadFile(recordedPath(t))
	if err != nil {
		t.Fatalf("read recorded contract: %v", err)
	}
	var records []Record
	if err := json.Unmarshal(data, &records); err != nil {
		t.Fatalf("decode recorded contract: %v", err)
	}
	seen := map[string]bool{}
	for _, r := range records {
		if seen[r.Key] {
			t.Errorf("duplicate contract key %q", r.Key)
		}
		seen[r.Key] = true
	}
	return records
}

// maxListedMismatches 逐条列举的 Mismatch 上限(总量另行汇总报告)。
const maxListedMismatches = 100

// reportMismatches 把比对结果逐条(截断到 maxListedMismatches)报为测试
// 失败并汇总计数。Mismatch 不静默、不跳过——如实红着,清单留给 T11 清零。
func reportMismatches(t *testing.T, mismatches []Mismatch) {
	t.Helper()
	for i, m := range mismatches {
		if i == maxListedMismatches {
			break
		}
		t.Errorf("MISMATCH %s\n  java = {stage:%s code:%q}\n  go   = {stage:%s code:%q}\n  sql  = %.120q",
			Key(m.Case), m.Java.Stage, m.Java.Code, m.Go.Stage, m.Go.Code, m.Case.SQL)
	}
	if len(mismatches) > maxListedMismatches {
		t.Errorf("... and %d more mismatches", len(mismatches)-maxListedMismatches)
	}
	if len(mismatches) > 0 {
		t.Errorf("total mismatches: %d / %d corpus cases (RED-pending-T11)", len(mismatches), len(LoadCorpus()))
	}
}

// TestContractAgainstRecorded 读提交的 parse-verdicts.json(Java 侧参考
// 契约),对每条现算 GoVerdict 比对——M1 语义等价验收,离线可跑(不依赖
// java)。Java later ≡ Go accept;parse/classify 需 Stage+Code 相等。
func TestContractAgainstRecorded(t *testing.T) {
	records := loadRecords(t)
	java := RecordsByVerdict(records)
	corpus := LoadCorpus()

	// 契约漂移护栏:记录数与语料数必须一致,记录里的 key 必须都在语料中。
	if len(records) != len(corpus) {
		t.Errorf("recorded contract has %d records, corpus has %d cases", len(records), len(corpus))
	}
	inCorpus := make(map[string]bool, len(corpus))
	for _, c := range corpus {
		inCorpus[Key(c)] = true
	}
	for _, r := range records {
		if !inCorpus[r.Key] {
			t.Errorf("recorded key %q not present in corpus (corpus or mapping drifted)", r.Key)
		}
	}

	reportMismatches(t, Compare(corpus, java))
}

// TestContractAgainstLiveJava 存在 java + jar 时对全语料实时比对(不读
// 记录文件);否则跳过。整轮是数百次 JVM 启动(约 10 分钟量级),超过
// go test 默认 10 分钟超时,故另需显式开启 CONTRACT_LIVE_JAVA=1 并以
// `-timeout 30m` 运行:
//
//	CONTRACT_LIVE_JAVA=1 go test ./contract/ -run TestContractAgainstLiveJava -timeout 30m
func TestContractAgainstLiveJava(t *testing.T) {
	if os.Getenv("CONTRACT_LIVE_JAVA") == "" {
		t.Skip("live Java comparison is opt-in: set CONTRACT_LIVE_JAVA=1 (hundreds of JVM starts; run with -timeout 30m)")
	}
	if _, err := exec.LookPath("java"); err != nil {
		t.Skip("java not found in PATH")
	}
	if _, err := os.Stat(JarPath()); err != nil {
		t.Skipf("jar not built at %s (mvn package first)", JarPath())
	}
	metadata := DefaultMetadataPath()
	corpus := LoadCorpus()
	java := make(map[string]Verdict, len(corpus))
	for _, c := range corpus {
		run, err := RunJava(c.Dialect, metadata, c.SQL)
		if err != nil {
			t.Fatalf("run java for %s: %v", Key(c), err)
		}
		java[Key(c)] = VerdictFromJava(run)
		if (len(java))%50 == 0 {
			t.Logf("live: %d/%d", len(java), len(corpus))
		}
	}
	reportMismatches(t, Compare(corpus, java))
}

// GoVerdict 的单元级护栏:panic 兜底必须折算成 parse 拒绝而不是崩溃 harness。
func TestGoVerdictPanicFallback(t *testing.T) {
	got := GoVerdict("SELECT 1", "postgresql")
	if got != (Verdict{Stage: StageAccept}) {
		t.Fatalf("SELECT 1 = %+v, want accept", got)
	}
	if got := GoVerdict("SELECT &&&", "postgresql"); got.Stage != StageParse {
		t.Fatalf("lexical-error stmt stage = %q, want parse", got.Stage)
	}
	if got := GoVerdict("UPDATE t SET a=1", "postgresql"); got != (Verdict{Stage: StageClassify, Code: "UNSUPPORTED_STATEMENT"}) {
		t.Fatalf("UPDATE = %+v, want classify/UNSUPPORTED_STATEMENT", got)
	}
	if got := GoVerdict("GRANT SELECT ON t TO u", "postgresql"); got != (Verdict{Stage: StageParse, Code: "PARSE_ERROR"}) {
		t.Fatalf("GRANT = %+v, want parse/PARSE_ERROR", got)
	}
}

// Compare 语义护栏:判定差异必须显形为 Mismatch,Java later 必须折算为
// accept——防止比对因 map/折算 bug 而空洞地绿。
func TestCompareDetectsMismatchAndLaterEquivalence(t *testing.T) {
	corpus := LoadCorpus()
	first := Key(corpus[0])

	// 以提交的契约(真实 Java 判定)为底,注入差异必须显形为恰 1 条 mismatch。
	java := RecordsByVerdict(loadRecords(t))
	java[first] = Verdict{Stage: StageParse, Code: "PARSE_ERROR"}
	if ms := Compare(corpus, java); len(ms) != 1 || Key(ms[0].Case) != first {
		t.Fatalf("want exactly 1 mismatch on %s, got %d", first, len(ms))
	}
	// Java later ≡ Go accept → 0 mismatch。
	java[first] = Verdict{Stage: StageLater, Code: "VALIDATION_ERROR"}
	if ms := Compare(corpus, java); len(ms) != 0 {
		t.Fatalf("later must be equivalent to accept, got %d mismatches", len(ms))
	}
	// 缺 key → mismatch(Stage missing)。
	delete(java, first)
	if ms := Compare(corpus, java); len(ms) != 1 || ms[0].Java.Stage != "missing" {
		t.Fatalf("missing key must mismatch with stage missing, got %d mismatches", len(ms))
	}
	// parse 阶段 Code 不一致 → mismatch。
	corpus[0].SQL = "SELECT 1"
	java[first] = Verdict{Stage: StageParse, Code: "OTHER"}
	if ms := Compare(corpus, java); len(ms) != 1 {
		t.Fatalf("code difference at same stage must mismatch, got %d", len(ms))
	}
}

// Key 与语料映射的稳定性护栏。
func TestCorpusKeysAndDialects(t *testing.T) {
	corpus := LoadCorpus()
	if len(corpus) == 0 {
		t.Fatal("corpus is empty")
	}
	for _, c := range corpus {
		if c.SQL == "" {
			t.Errorf("empty statement in %s #%d", c.File, c.Ordinal)
		}
		want := fmt.Sprintf("%s#%d@%s", c.File, c.Ordinal, c.Dialect)
		if Key(c) != want {
			t.Fatalf("Key(%+v) = %q, want %q", c, Key(c), want)
		}
		// 当前 13 个语料文件名均不含 mysql/trino,按映射规则应全为 postgresql;
		// 若未来加入含这些子串的文件,规则本身由 dialectForFile 单测护栏。
		if c.Dialect != "postgresql" {
			t.Errorf("%s: dialect = %q, want postgresql (filename lacks mysql/trino)", c.File, c.Dialect)
		}
	}
	// 方言映射规则护栏:含 mysql→mysql、含 trino→trino、其余→postgresql。
	for base, want := range map[string]string{
		"tpcds-mysql-crlf.sql":            "mysql",
		"tpcds_mysql_oneline.sql":         "mysql",
		"tpcds-trino-robustness.sql":      "trino",
		"tpcds_trino_common_cases.sql":    "trino",
		"tpcds_common_cases.sql":          "postgresql",
		"write-statements.sql":            "postgresql",
		"tpcds-mysql-and-trino-mixed.sql": "mysql", // mysql 优先
	} {
		if got := dialectForFile(base); got != want {
			t.Errorf("dialectForFile(%q) = %q, want %q", base, got, want)
		}
	}
	// 语料文件清单与仓库实际内容一致性抽查。
	for _, rel := range []string{
		"mask-engine/tpcds/queries/tpcds_crlf.sql",
		"mask-engine/src/test/resources/golden/write-statements.sql",
	} {
		if _, err := os.Stat(filepath.Join(repotool.Root(), filepath.FromSlash(rel))); err != nil {
			t.Errorf("corpus file missing: %s", rel)
		}
	}
}
