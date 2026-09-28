package contract

import "testing"

// VerdictFromJava 纯函数分类测试:用 jar 实测的 stderr 形态(逐字摘自
// `java -jar mask-core/target/sql-mask.jar --metadata ... --sql ... --dialect ...`)
// 构造 JavaRun,断言 M1 差分判定规则:
//
//	exit 0 → accept;stderr 含 "[PARSE_ERROR] statement" → parse;
//	含 "[UNSUPPORTED_STATEMENT] statement" → classify;
//	其余任何码或失败形态 → later(M1 视为解析接受)。
func TestVerdictFromJava(t *testing.T) {
	tests := []struct {
		name string
		run  JavaRun
		want Verdict
	}{
		{
			name: "exit0 accept",
			run:  JavaRun{Exit: 0, Stdout: "SELECT 1;\n"},
			want: Verdict{Stage: StageAccept},
		},
		{
			name: "exit0 with noisy stderr still accept",
			run:  JavaRun{Exit: 0, Stderr: "some warning\n"},
			want: Verdict{Stage: StageAccept},
		},
		{
			name: "parse error",
			run: JavaRun{Exit: 1, Stderr: "sql-mask: [PARSE_ERROR] statement 1: parse error (postgresql): " +
				"Incorrect syntax near the keyword 'GRANT' at line 1, column 1.\nWas expecting one of:\n"},
			want: Verdict{Stage: StageParse, Code: "PARSE_ERROR"},
		},
		{
			name: "unsupported statement classify",
			run: JavaRun{Exit: 1, Stderr: "sql-mask: [UNSUPPORTED_STATEMENT] statement 1: unsupported statement kind UPDATE; " +
				"only SELECT and WITH ... SELECT queries are supported in this version\n"},
			want: Verdict{Stage: StageClassify, Code: "UNSUPPORTED_STATEMENT"},
		},
		{
			name: "config error from metadata is later",
			run:  JavaRun{Exit: 1, Stderr: "sql-mask: [CONFIG_ERROR] Table 'tpcds.customer' not found in metadata\n"},
			want: Verdict{Stage: StageLater, Code: "CONFIG_ERROR"},
		},
		{
			name: "bad dialect exit2 is later",
			run:  JavaRun{Exit: 2, Stderr: "sql-mask: [CONFIG_ERROR] unsupported dialect 'bogus'; supported dialects: postgresql, trino, mysql, hive, sparksql\n"},
			want: Verdict{Stage: StageLater, Code: "CONFIG_ERROR"},
		},
		{
			name: "unexpected error without code is later with empty code",
			run:  JavaRun{Exit: 1, Stderr: "sql-mask: unexpected error: boom\n"},
			want: Verdict{Stage: StageLater, Code: ""},
		},
		{
			name: "empty stderr nonzero exit is later",
			run:  JavaRun{Exit: 1},
			want: Verdict{Stage: StageLater, Code: ""},
		},
		{
			name: "parse error marker without statement prefix is later",
			run:  JavaRun{Exit: 1, Stderr: "sql-mask: [PARSE_ERROR] weird shape\n"},
			want: Verdict{Stage: StageLater, Code: "PARSE_ERROR"},
		},
		{
			name: "unsupported marker without statement prefix is later",
			run:  JavaRun{Exit: 1, Stderr: "sql-mask: [UNSUPPORTED_STATEMENT] weird shape\n"},
			want: Verdict{Stage: StageLater, Code: "UNSUPPORTED_STATEMENT"},
		},
		{
			name: "classification marker wins over later code elsewhere in stderr",
			run: JavaRun{Exit: 1, Stderr: "sql-mask: [UNSUPPORTED_STATEMENT] statement 2: unsupported statement kind MERGE; only SELECT and WITH ... SELECT queries are supported in this version\n" +
				"sql-mask: [CONFIG_ERROR] something else\n"},
			want: Verdict{Stage: StageClassify, Code: "UNSUPPORTED_STATEMENT"},
		},
		{
			name: "io error is later",
			run:  JavaRun{Exit: 1, Stderr: "sql-mask: [IO_ERROR] cannot read metadata file 'x.yaml'\n"},
			want: Verdict{Stage: StageLater, Code: "IO_ERROR"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := VerdictFromJava(tt.run)
			if got != tt.want {
				t.Fatalf("VerdictFromJava(%+v) = %+v, want %+v", tt.run, got, tt.want)
			}
		})
	}
}

// 判定只看 exit 与 stderr;stdout 内容(改写结果)不影响解析期契约。
func TestVerdictFromJavaIgnoresStdout(t *testing.T) {
	if got := VerdictFromJava(JavaRun{Exit: 0, Stdout: "anything"}); got.Stage != StageAccept {
		t.Fatalf("stage = %q, want accept", got.Stage)
	}
}
