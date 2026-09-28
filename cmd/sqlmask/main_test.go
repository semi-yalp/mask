package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestParseEntry冒烟 --parse 调试入口:混合文件逐语句输出 ok/错误行。
func TestParseEntrySmoke(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "mixed.sql")
	src := "SELECT 1;\nUPDATE customer SET c_email_address = 'x';\nCOMMIT x;\n"
	if err := os.WriteFile(path, []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := runParse(path, "postgresql"); err != nil {
		t.Fatal(err)
	}
}

// TestParseEntryUnknownDialect 未知方言报错非零。
func TestParseEntryUnknownDialect(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "x.sql")
	if err := os.WriteFile(path, []byte("SELECT 1;"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := runParse(path, "nope"); err == nil {
		t.Fatal("unknown dialect should error")
	} else if !strings.Contains(err.Error(), "unsupported dialect") {
		t.Fatalf("err = %v", err)
	}
}
