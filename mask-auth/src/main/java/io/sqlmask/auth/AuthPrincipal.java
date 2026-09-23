package io.sqlmask.auth;

import java.util.List;

/**
 * Authenticated console identity: the LDAP username, its display name, the
 * LDAP group names (first RDN value of each group DN) and the mapped role.
 * Immutable value carried inside the session token and, after verification,
 * on the request as attribute {@link AuthTokens#PRINCIPAL_ATTRIBUTE}.
 */
public record AuthPrincipal(String username, String displayName, Role role, List<String> groups) {

  public AuthPrincipal {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }
    groups = groups == null ? List.of() : List.copyOf(groups);
  }
}
