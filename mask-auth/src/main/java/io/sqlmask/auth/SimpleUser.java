package io.sqlmask.auth;

import java.util.List;

/**
 * A local user of the simple auth mode: credentials plus the role and group
 * claims that end up in the issued bearer token.
 */
public record SimpleUser(String username, String displayName, Role role, List<String> groups,
                         String passwordHash, boolean enabled) {

  public SimpleUser {
    groups = groups == null ? List.of() : List.copyOf(groups);
    if (role == null) {
      role = Role.USER;
    }
  }
}
