// masklite CLI：--metadata <yaml> (--sql <text> | --input <file>)，
// 改写后的 SQL 逐语句一行打到 stdout（各带尾分号）；退出码 0 成功、
// 1 改写失败、2 用法错误（缺值/未知参数/缺 --metadata/二选一违反统一
// 按用法错误处理，消息后跟 usage 行——对齐 Java MaskLite.main）。
package main

import (
	"fmt"
	"os"

	"io.masklite/go/maskerr"
	"io.masklite/go/masklite"
)

type cliOptions struct {
	metadata string
	sql      *string
	input    *string
}

func main() {
	options, err := parseArguments(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		fmt.Fprintln(os.Stderr, "usage: masklite --metadata <yaml> (--sql <text> | --input <file>)")
		os.Exit(2)
	}
	mask, err := masklite.FromYamlFile(options.metadata)
	if err != nil {
		fail(err)
	}
	text := ""
	if options.sql != nil {
		text = *options.sql
	} else {
		data, readErr := os.ReadFile(*options.input)
		if readErr != nil {
			fmt.Fprintln(os.Stderr, "fatal: "+readErr.Error())
			os.Exit(1)
		}
		text = string(data)
	}
	statements, err := mask.RewriteStatements(text)
	if err != nil {
		fail(err)
	}
	for _, s := range statements {
		fmt.Println(s.RewrittenSQL + ";")
	}
}

// parseArguments 解析参数；任何用法问题返回含用途化消息的错误（→ exit 2）。
func parseArguments(args []string) (cliOptions, error) {
	opts := cliOptions{}
	for i := 0; i < len(args); i++ {
		option := args[i]
		if i+1 >= len(args) {
			return opts, fmt.Errorf("missing value for %s", option)
		}
		value := args[i+1]
		i++
		switch option {
		case "--metadata":
			opts.metadata = value
		case "--sql":
			v := value
			opts.sql = &v
		case "--input":
			v := value
			opts.input = &v
		default:
			return opts, fmt.Errorf("unknown argument: %s", option)
		}
	}
	if opts.metadata == "" {
		return opts, fmt.Errorf("--metadata is required")
	}
	if (opts.sql == nil) == (opts.input == nil) {
		return opts, fmt.Errorf("exactly one of --sql / --input is required")
	}
	return opts, nil
}

func fail(err error) {
	if me, ok := err.(*maskerr.Error); ok {
		fmt.Fprintln(os.Stderr, me.Message)
	} else {
		fmt.Fprintln(os.Stderr, "fatal: "+err.Error())
	}
	os.Exit(1)
}
