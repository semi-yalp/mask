package contract

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"io.sqlmask/go/internal/repotool"
	"io.sqlmask/go/split"
)

// Case 是契约的一条语料:某个文件里的第 Ordinal 条语句及其目标方言。
// File 为仓库根相对路径(正斜杠),Ordinal 为该文件内 split.Statements
// 切出的序号(0 起)。
type Case struct {
	File    string
	Ordinal int
	SQL     string
	Dialect string
}

// Key 返回契约条目的稳定键:file#ordinal@dialect,contractgen 与 diff_test
// 共用(契约 json 的 key 字段即此值)。
func Key(c Case) string {
	return c.File + "#" + strconv.Itoa(c.Ordinal) + "@" + c.Dialect
}

// 语料文件清单(仓库根相对路径):tpcds queries 全部 *.sql(文件名排序)+
// golden write-statements.sql。全部文件全部语句进契约,不做任何特殊化
// ——不支持的语句正是契约的一部分。
func corpusFiles() []string {
	root := repotool.Root()
	qdir := filepath.Join(root, "mask-engine", "tpcds", "queries")
	entries, err := os.ReadDir(qdir)
	if err != nil {
		panic("contract: read tpcds queries dir: " + err.Error())
	}
	var names []string
	for _, e := range entries {
		if !e.IsDir() && strings.EqualFold(filepath.Ext(e.Name()), ".sql") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	files := make([]string, 0, len(names)+1)
	for _, n := range names {
		files = append(files, "mask-engine/tpcds/queries/"+n)
	}
	files = append(files, "mask-engine/src/test/resources/golden/write-statements.sql")
	// T11:Go 侧 parse-reject 语料(Java postgresql 解析期拒绝形态集合),
	// 守护契约的 parse 判定面(T12 起该目录扩展方言差异语料)。
	files = append(files, extraCorpusFiles()...)
	return files
}

// extraCorpusFiles 列出 testdata/extra/ 下已提交的语料文件(不存在则跳过,
// 便于语料增量演进)。文件名映射沿用 dialectForFile。
func extraCorpusFiles() []string {
	root := repotool.Root()
	dir := filepath.Join(root, "testdata", "extra")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var files []string
	for _, e := range entries {
		if !e.IsDir() && strings.EqualFold(filepath.Ext(e.Name()), ".sql") {
			files = append(files, "testdata/extra/"+e.Name())
		}
	}
	sort.Strings(files)
	return files
}

// dialectForFile 按文件名映射目标方言:含 mysql→mysql、含 trino→trino、
// 含 hive→hive、含 sparksql→sparksql(T12 testdata/extra 语料)、其余→
// postgresql。
func dialectForFile(base string) string {
	l := strings.ToLower(base)
	switch {
	case strings.Contains(l, "mysql"):
		return "mysql"
	case strings.Contains(l, "trino"):
		return "trino"
	case strings.Contains(l, "sparksql"):
		return "sparksql"
	case strings.Contains(l, "hive"):
		return "hive"
	default:
		return "postgresql"
	}
}

// LoadCorpus 扫描全部语料文件,按 split.Statements(Java 语义)切成单条
// 语句,返回全部 Case(文件按上列顺序、语句按切分顺序,确定性)。文件缺失
// 或不可读直接 panic——契约语料是提交进仓库的固定内容,缺失属环境错误。
func LoadCorpus() []Case {
	root := repotool.Root()
	var out []Case
	for _, rel := range corpusFiles() {
		data, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(rel)))
		if err != nil {
			panic("contract: read corpus file " + rel + ": " + err.Error())
		}
		dialectName := dialectForFile(filepath.Base(rel))
		for i, stmt := range split.Statements(string(data)) {
			out = append(out, Case{File: rel, Ordinal: i, SQL: stmt, Dialect: dialectName})
		}
	}
	return out
}

// Record 是契约 json 的一条记录:verdict 为 [stage, code] 二元数组。
type Record struct {
	Key     string    `json:"key"`
	Dialect string    `json:"dialect"`
	SQL     string    `json:"sql"`
	Verdict [2]string `json:"verdict"`
}

// VerdictOf 取记录中的判定。
func VerdictOf(r Record) Verdict {
	return Verdict{Stage: r.Verdict[0], Code: r.Verdict[1]}
}

// RecordsByVerdict 把契约记录折成 key→Verdict 映射,供 Compare 使用。
func RecordsByVerdict(records []Record) map[string]Verdict {
	m := make(map[string]Verdict, len(records))
	for _, r := range records {
		m[r.Key] = VerdictOf(r)
	}
	return m
}
