package io.sqlmask.auth;

/**
 * Authentication failures with the HTTP-facing error code the login endpoint
 * and the bearer gate translate into status + JSON body. Codes are part of
 * the API contract; do not rename them.
 */
public final class AuthException extends RuntimeException {

  public enum Code {
    /** Wrong username or password — deliberately indistinguishable. */
    INVALID_CREDENTIALS,
    /** LDAP unreachable / timed out: fail closed, no fallback path. */
    LDAP_UNAVAILABLE,
    /** Login endpoint active but LDAP (or the token secret) is not configured. */
    LDAP_NOT_CONFIGURED,
    /** Malformed or tampered token. */
    INVALID_TOKEN,
    /** Token signature was valid but the expiry has passed. */
    EXPIRED_TOKEN,
    /** Environment misconfiguration detected at construction time. */
    CONFIG_ERROR
  }

  private final Code code;

  public AuthException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public AuthException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
