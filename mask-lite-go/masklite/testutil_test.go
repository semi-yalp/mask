package masklite

import (
	"fmt"
	"os"
)

// tpcdsQueryName 生成 q01..q99 文件名。
func tpcdsQueryName(i int) string {
	return fmt.Sprintf("q%02d.sql", i)
}

func readFile(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
