// contractgen 用 Java 版 CLI 对全语料逐条生成"解析期接受/拒绝"参考契约,
// 写入 testdata/contract/parse-verdicts.json(结果提交进仓库)。对每条语料
// 顺序调 RunJava(不并发轰 java;失败语句 exit=1 属正常契约数据),经
// VerdictFromJava 折算后记录 {key, dialect, sql, verdict:[stage, code]}。
// 契约只记录 Java 侧结果——Go 侧判定由 diff_test 现算比对,不写死。
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"io.sqlmask/go/contract"
	"io.sqlmask/go/internal/repotool"
)

func main() {
	corpus := contract.LoadCorpus()
	metadata := contract.DefaultMetadataPath()
	records := make([]contract.Record, 0, len(corpus))
	for i, c := range corpus {
		run, err := contract.RunJava(c.Dialect, metadata, c.SQL)
		if err != nil {
			fmt.Fprintf(os.Stderr, "contractgen: java 运行失败(%s): %v\n", contract.Key(c), err)
			os.Exit(1)
		}
		v := contract.VerdictFromJava(run)
		records = append(records, contract.Record{
			Key:     contract.Key(c),
			Dialect: c.Dialect,
			SQL:     c.SQL,
			Verdict: [2]string{v.Stage, v.Code},
		})
		if (i+1)%50 == 0 {
			fmt.Fprintf(os.Stderr, "contractgen: %d/%d\n", i+1, len(corpus))
		}
	}
	data, err := json.MarshalIndent(records, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "contractgen: marshal: %v\n", err)
		os.Exit(1)
	}
	outPath := filepath.Join(repotool.Root(), "testdata", "contract", "parse-verdicts.json")
	if err := os.MkdirAll(filepath.Dir(outPath), 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "contractgen: mkdir: %v\n", err)
		os.Exit(1)
	}
	if err := os.WriteFile(outPath, append(data, '\n'), 0o644); err != nil {
		fmt.Fprintf(os.Stderr, "contractgen: write %s: %v\n", outPath, err)
		os.Exit(1)
	}
	fmt.Printf("contractgen: %d 条契约写入 %s\n", len(records), outPath)
}
