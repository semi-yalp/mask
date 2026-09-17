package io.sqlmask.query.error;

/** Fatal data-plane error with a machine-readable code: either one of this
 * service's own codes or a passthrough code from an upstream response. */
public class QueryException extends RuntimeException {

  /** Codes owned by mask-query; upstream codes pass through verbatim. */
  public static final String CONFIG_ERROR = "CONFIG_ERROR";
  public static final String INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND";
  public static final String INSTANCE_NOT_EXECUTABLE = "INSTANCE_NOT_EXECUTABLE";
  public static final String MULTI_STATEMENT = "MULTI_STATEMENT";
  public static final String WRITE_STATEMENT = "WRITE_STATEMENT";
  public static final String QUERY_BUSY = "QUERY_BUSY";
  public static final String QUERY_TIMEOUT = "QUERY_TIMEOUT";
  public static final String QUERY_ERROR = "QUERY_ERROR";
  public static final String CREDENTIAL_UNAVAILABLE = "CREDENTIAL_UNAVAILABLE";
  public static final String REWRITE_SERVICE_UNAVAILABLE = "REWRITE_SERVICE_UNAVAILABLE";
  public static final String UNSUPPORTED_ENGINE = "UNSUPPORTED_ENGINE";

  private final String code;
  /** True when the failure happened at or before the rewrite call — the
   * rewrite (REWRITE) audit event belongs to mask-core, so mask-query stays
   * silent. */
  private final boolean rewritePhase;

  public QueryException(String code, String message) {
    this(code, message, null);
  }

  public QueryException(String code, String message, Throwable cause) {
    this(code, message, cause, false);
  }

  public QueryException(String code, String message, Throwable cause, boolean rewritePhase) {
    super(message, cause);
    this.code = code;
    this.rewritePhase = rewritePhase;
  }

  public String code() { return code; }

  public boolean rewritePhase() { return rewritePhase; }
}
