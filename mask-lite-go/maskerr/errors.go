// Package maskerr 定义 mask-lite-go 的统一错误码与错误类型。
// Code 取值与 Java io.masklite.error.SqlMaskException.Code 枚举逐字对齐
// （14 个全部保留定义；mask-lite 内核实际只会触发前 7 个，其余为
// mask-engine 遗留枚举值，保持完整以便错误码兼容判断）。
package maskerr

// Code 是稳定的错误码字符串；判断错误用 errors.As(*Error) 后读 Code。
type Code string

// 与 Java Code 枚举逐字一致。
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

// Error 携带错误码与消息；Error() 输出 "CODE: message"。
type Error struct {
	Code    Code
	Message string
	Err     error
}

// New 构造一个不带底层原因的领域错误。
func New(code Code, msg string) *Error {
	return &Error{Code: code, Message: msg}
}

// Errorf 按格式构造领域错误；需要包装底层错误时手工填 Err 字段。
func Errorf(code Code, format string, args ...any) *Error {
	return &Error{Code: code, Message: sprintf(format, args...)}
}

// Error 实现 error 接口，格式与 Java SqlMaskException.getMessage 的展示层一致。
func (e *Error) Error() string {
	return string(e.Code) + ": " + e.Message
}

// Unwrap 支持 errors.Is / errors.As 链式判断。
func (e *Error) Unwrap() error { return e.Err }
