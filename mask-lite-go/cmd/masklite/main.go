// masklite CLI：--metadata <yaml> (--sql <text> | --input <file>)，
// 改写后的 SQL 逐语句一行打到 stdout（各带尾分号）；退出码 0 成功、
// 1 改写失败、2 用法错误（对齐 Java MaskLite.main）。
package main

import (
	"fmt"
	"os"

	"io.masklite/go/maskerr"
	"io.masklite/go/masklite"
)

func main() {
	var metadata, sql, input string
	args := os.Args[1:]
	i := 0
	for i < len(args) {
		switch args[i] {
		case "--metadata":
			i++
			if i >= len(args) {
				usage()
			}
			metadata = args[i]
		case "--sql":
			i++
			if i >= len(args) {
				usage()
			}
			sql = args[i]
		case "--input":
			i++
			if i >= len(args) {
				usage()
			}
			input = args[i]
		default:
			fmt.Fprintln(os.Stderr, "unknown argument: "+args[i])
			os.Exit(2)
		}
		i++
	}
	if metadata == "" || (sql == "") == (input == "") {
		usage()
	}
	mask, err := masklite.FromYamlFile(metadata)
	if err != nil {
		fail(err)
	}
	text := sql
	if sql == "" {
		data, readErr := os.ReadFile(input)
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

func usage() {
	fmt.Fprintln(os.Stderr, "usage: masklite --metadata <yaml> (--sql <text> | --input <file>)")
	os.Exit(2)
}

func fail(err error) {
	if me, ok := err.(*maskerr.Error); ok {
		fmt.Fprintln(os.Stderr, me.Message)
	} else {
		fmt.Fprintln(os.Stderr, "fatal: "+err.Error())
	}
	os.Exit(1)
}
