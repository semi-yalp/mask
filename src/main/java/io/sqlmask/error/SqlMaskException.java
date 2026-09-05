package io.sqlmask.error;

/**
 * Fatal processing error carrying a machine-readable error code suitable for
 * stderr diagnostics.
 */
public class SqlMaskException extends RuntimeException {

  public enum Code {
    CONFIG_ERROR,
    PARSE_ERROR,
    VALIDATION_ERROR,
    UNSUPPORTED_STATEMENT,
    LINEAGE_UNKNOWN,
    REWRITE_ERROR,
    IO_ERROR
  }

  private final Code code;

  public SqlMaskException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public SqlMaskException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code getCode() {
    return code;
  }
}
