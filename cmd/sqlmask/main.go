// Command sqlmask 是 sql-mask Go 版的 CLI 入口。
// M1 阶段仅支持 --version;完整 CLI 子命令在 M4 接入。
package main

import (
	"fmt"
	"os"
)

const version = "sql-mask-go 0.1.0"

const usage = `usage: sqlmask <command> [flags]

commands are coming in M4; currently supported:
  --version    print version
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	switch os.Args[1] {
	case "--version":
		fmt.Println(version)
	default:
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
}
