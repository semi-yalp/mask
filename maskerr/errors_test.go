package maskerr

import (
	"errors"
	"strings"
	"testing"
)

func TestCodeValuesMatchJava(t *testing.T) {
	// 直接引用全部 14 个常量:值与 Java Code 枚举逐字一致(编译期保证存在,断言防手误改名)。
	pairs := map[Code]string{
		ConfigError:                   "CONFIG_ERROR",
		ParseError:                    "PARSE_ERROR",
		ValidationError:               "VALIDATION_ERROR",
		UnsupportedStatement:          "UNSUPPORTED_STATEMENT",
		LineageUnknown:                "LINEAGE_UNKNOWN",
		RewriteError:                  "REWRITE_ERROR",
		IOError:                       "IO_ERROR",
		PolicyServiceUnavailable:      "POLICY_SERVICE_UNAVAILABLE",
		PolicyInstanceNotFound:        "POLICY_INSTANCE_NOT_FOUND",
		IntrospectError:               "INTROSPECT_ERROR",
		MetadataInstanceNotFound:      "METADATA_INSTANCE_NOT_FOUND",
		MetadataInstanceExists:        "METADATA_INSTANCE_EXISTS",
		MetadataCredentialUnavailable: "METADATA_CREDENTIAL_UNAVAILABLE",
		MetadataServiceUnavailable:    "METADATA_SERVICE_UNAVAILABLE",
	}
	for code, want := range pairs {
		if code != Code(want) {
			t.Fatalf("code %q != %q", code, want)
		}
	}
}

func TestErrorMessageFormat(t *testing.T) {
	err := Errorf(ParseError, "statement %d: parse error (%s): boom", 1, "postgresql")
	if err.Code != ParseError || !strings.Contains(err.Error(), "statement 1: parse error (postgresql): boom") {
		t.Fatalf("unexpected: %v", err)
	}
	if !errors.Is(err, err.Err) && err.Err != nil {
		t.Fatalf("wrap lost")
	}
}
