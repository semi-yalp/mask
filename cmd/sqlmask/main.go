// Command sqlmask 是 sql-mask Go 版的 CLI 入口。
// M1 阶段支持 --version 与 --parse 调试入口;完整 CLI 子命令在 M4 接入。
package main

import (
	"fmt"
	"os"

	"io.sqlmask/go/dialect"
	"io.sqlmask/go/engine"
	"io.sqlmask/go/maskerr"
	"io.sqlmask/go/parser"
	"io.sqlmask/go/split"
)

const version = "sql-mask-go 0.1.0"

const usage = `usage: sqlmask [flags]

  --version              print version
  --parse <file>         parse the file's statements (debug entry, M1);
                         prints one line per statement: "#N ok <type>",
                         "#N PARSE_ERROR: ..." or "#N <classify error>"
  --dialect <name>       dialect for --parse (default postgresql)
`

func main() {
	var parseFile, dialectName string
	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--version":
			fmt.Println(version)
			return
		case "--parse":
			if i+1 >= len(args) {
				fatal("--parse requires a file argument")
			}
			i++
			parseFile = args[i]
		case "--dialect":
			if i+1 >= len(args) {
				fatal("--dialect requires a name argument")
			}
			i++
			dialectName = args[i]
		default:
			fmt.Fprint(os.Stderr, usage)
			os.Exit(2)
		}
	}
	if parseFile == "" {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	if err := runParse(parseFile, dialectName); err != nil {
		fmt.Fprintf(os.Stderr, "sql-mask: %v\n", err)
		os.Exit(1)
	}
}

// runParse 拆分文件为语句后逐条解析并打印结果(ok <类型> / 错误),人工
// 验证入口;输出到 stdout,退出码 0(单条语句失败不改变退出码——本入口
// 用于观察,不用于判定;判定走 go test ./contract/)。
func runParse(path, dialectName string) error {
	if dialectName == "" {
		dialectName = "postgresql"
	}
	prof, err := dialect.ByName(dialectName)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return maskerr.Errorf(maskerr.IOError, "cannot read file '%s': %v", path, err)
	}
	for i, sql := range split.Statements(string(data)) {
		p := parser.New(prof, sql)
		stmt, err := p.ParseStatement()
		if err != nil {
			fmt.Printf("#%d %v\n", i, err)
			continue
		}
		if err := engine.Classify(stmt, i); err != nil {
			fmt.Printf("#%d %v\n", i, err)
			continue
		}
		fmt.Printf("#%d ok %T\n", i, stmt)
	}
	return nil
}

func fatal(msg string) {
	fmt.Fprintln(os.Stderr, msg)
	os.Exit(2)
}
