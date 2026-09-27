// Package repotool 定位仓库根目录,供测试与内部工具复用。
package repotool

import (
	"os"
	"path/filepath"
	"runtime"
	"sync"
)

var (
	rootOnce sync.Once
	rootDir  string
)

// Root 返回包含 go.mod 的仓库根目录绝对路径,结果在首次调用时缓存。
// 优先从可执行文件位置向上查找,失败时回退到本包源码位置;找不到 go.mod 时 panic。
func Root() string {
	rootOnce.Do(func() {
		for _, start := range []string{fromExecutable(), fromSource()} {
			if dir := findGoModDir(start); dir != "" {
				rootDir = dir
				return
			}
		}
		panic("repotool: go.mod not found")
	})
	return rootDir
}

// fromExecutable 返回当前可执行文件所在目录(测试二进制在临时目录,通常找不到 go.mod)。
func fromExecutable() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	return filepath.Dir(exe)
}

// fromSource 返回本包源码文件所在目录(go test / 源码内运行时可靠)。
func fromSource() string {
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		return ""
	}
	return filepath.Dir(file)
}

// findGoModDir 从 start 逐级向上找 go.mod,返回其所在目录;到根仍未找到返回空串。
func findGoModDir(start string) string {
	dir, err := filepath.Abs(start)
	if err != nil {
		return ""
	}
	for {
		if info, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil && !info.IsDir() {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return ""
		}
		dir = parent
	}
}
