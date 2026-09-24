package io.sqlmask.auth;

/**
 * Console authentication mode. {@code NONE} is the default — the platform is
 * fully usable without authentication (the "functionality first" posture);
 * {@code SIMPLE} backs login with the local user table; {@code LDAP}
 * delegates to the corporate directory.
 */
public enum AuthMode {

  NONE, SIMPLE, LDAP;

  /** Lenient parse: unknown values fall back to {@link #NONE} with the raw
   * value surfaced by the caller for logging. */
  public static AuthMode parse(String value) {
    if (value == null) {
      return NONE;
    }
    return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "simple", "local" -> SIMPLE;
      case "ldap" -> LDAP;
      default -> NONE;
    };
  }
}
