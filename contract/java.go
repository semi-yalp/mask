// Package contract 承载 M1 语义等价验收的差分 harness:用 Java 版 CLI 对全
// 语料生成"解析期接受/拒绝"参考契约(parse-verdicts.json,提交进仓库),
// Go 侧逐条比对(GoVerdict vs Java 判定,M1 绿 = 逐条相等且 Java later ≡
// Go accept)。见 .superpowers/sdd/2026-09-27-go-port-m1-parser/task-10-brief.md。
package contract

import (
	"bytes"
	"errors"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"

	"io.sqlmask/go/internal/repotool"
)

// JavaRun 记录一次 Java CLI 进程的结果:退出码与两个流的原文。
// 失败语句 exit=1 属正常契约数据,不作为 RunJava 的 error。
type JavaRun struct {
	Exit   int
	Stdout string
	Stderr string
}

// JarPath 返回 Java 版 shaded jar 的仓库绝对路径(Windows 下直接传给
// java -jar,不经 shell 展开)。
func JarPath() string {
	return filepath.Join(repotool.Root(), "mask-core", "target", "sql-mask.jar")
}

// DefaultMetadataPath 返回全语料共用的 metadata yaml(tpcds 元数据):
// write-statements.sql 若因 metadata 缺表报 CONFIG_ERROR,按判定规则归
// later,不影响解析期契约。
func DefaultMetadataPath() string {
	return filepath.Join(repotool.Root(), "mask-engine", "tpcds", "metadata.yaml")
}

// RunJava 执行 `java -jar <repo>/mask-core/target/sql-mask.jar --metadata
// <metadataPath> --sql <sql> --dialect <dialectName>`,不经 shell;进程正常
// 退出(含 Java 侧失败语句的 exit 1/2)时返回结果与 nil error,仅 java 无法
// 启动(命令不存在等)返回 error——契约生成必须真实运行,此时调用方应报错退出。
func RunJava(dialectName, metadataPath, sql string) (JavaRun, error) {
	cmd := exec.Command("java", "-jar", JarPath(),
		"--metadata", metadataPath, "--sql", sql, "--dialect", dialectName)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	run := JavaRun{Stdout: stdout.String(), Stderr: stderr.String()}
	if err == nil {
		return run, nil
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		run.Exit = exitErr.ExitCode()
		return run, nil
	}
	return run, err
}

// 判定标记(stderr 形态逐字来自 SqlMaskApplication.call():
// "sql-mask: [" + code + "] " + message,解析期错误的 message 以
// "statement N: " 开头)。
const (
	parseMarker    = "[PARSE_ERROR] statement"
	classifyMarker = "[UNSUPPORTED_STATEMENT] statement"
)

// javaCodeRe 从 stderr 提取第一个 [CODE] 形态的错误码(later 记录用)。
var javaCodeRe = regexp.MustCompile(`\[([A-Z_]+)\]`)

// VerdictFromJava 按 M1 差分语义把一次 Java CLI 运行折算成解析期判定:
// exit 0 → accept;stderr 含 "[PARSE_ERROR] statement" → parse;
// 含 "[UNSUPPORTED_STATEMENT] statement" → classify;其余任何码或其它失败
// 形态(usage exit 2、CONFIG_ERROR、IO_ERROR、无 [CODE] 崩溃……)→ later
// ——M1 把 later 视为解析接受(这些是 M2+ 阶段才判定的失败)。
func VerdictFromJava(r JavaRun) Verdict {
	switch {
	case r.Exit == 0:
		return Verdict{Stage: StageAccept}
	case strings.Contains(r.Stderr, parseMarker):
		return Verdict{Stage: StageParse, Code: "PARSE_ERROR"}
	case strings.Contains(r.Stderr, classifyMarker):
		return Verdict{Stage: StageClassify, Code: "UNSUPPORTED_STATEMENT"}
	default:
		code := ""
		if m := javaCodeRe.FindStringSubmatch(r.Stderr); m != nil {
			code = m[1]
		}
		return Verdict{Stage: StageLater, Code: code}
	}
}
