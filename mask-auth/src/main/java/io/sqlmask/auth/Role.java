package io.sqlmask.auth;

/**
 * Console role derived from LDAP group membership. Rank order matters:
 * {@link #atLeast} is the single comparison authorization rules rely on.
 */
public enum Role {
  USER,
  AUDITOR,
  ADMIN;

  public boolean atLeast(Role other) {
    return ordinal() >= other.ordinal();
  }

  /** Case-insensitive claim parsing; anything unknown is a malformed token. */
  static Role parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("missing role claim");
    }
    return Role.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
  }
}
