// Package maskerr 定义 sql-mask Go 版的统一错误码与错误类型。
// Code 取值与 Java 版 io.sqlmask.error.SqlMaskException.Code 枚举逐字对齐。
package maskerr

import "fmt"

// Code 错误码,取值与 Java Code 枚举逐字一致。
type Code string

// 与 Java SqlMaskException.Code 枚举一一对应的 14 个错误码。
const (
	ConfigError                   Code = "CONFIG_ERROR"
	ParseError                    Code = "PARSE_ERROR"
	ValidationError               Code = "VALIDATION_ERROR"
	UnsupportedStatement          Code = "UNSUPPORTED_STATEMENT"
	LineageUnknown                Code = "LINEAGE_UNKNOWN"
	RewriteError                  Code = "REWRITE_ERROR"
	IOError                       Code = "IO_ERROR"
	PolicyServiceUnavailable      Code = "POLICY_SERVICE_UNAVAILABLE"
	PolicyInstanceNotFound        Code = "POLICY_INSTANCE_NOT_FOUND"
	IntrospectError               Code = "INTROSPECT_ERROR"
	MetadataInstanceNotFound      Code = "METADATA_INSTANCE_NOT_FOUND"
	MetadataInstanceExists        Code = "METADATA_INSTANCE_EXISTS"
	MetadataCredentialUnavailable Code = "METADATA_CREDENTIAL_UNAVAILABLE"
	MetadataServiceUnavailable    Code = "METADATA_SERVICE_UNAVAILABLE"
)

// Error 携带错误码的统一错误类型;Err 为可选的底层错误。
type Error struct {
	Code    Code
	Message string
	Err     error
}

// New 创建带消息的错误。
func New(code Code, msg string) *Error {
	return &Error{Code: code, Message: msg}
}

// Errorf 按格式创建错误。
func Errorf(code Code, format string, args ...any) *Error {
	return &Error{Code: code, Message: fmt.Sprintf(format, args...)}
}

// Error 返回 "CODE: message" 风格的字符串。
func (e *Error) Error() string {
	return string(e.Code) + ": " + e.Message
}

// Unwrap 透传底层错误,支持 errors.Is / errors.As。
func (e *Error) Unwrap() error {
	return e.Err
}
