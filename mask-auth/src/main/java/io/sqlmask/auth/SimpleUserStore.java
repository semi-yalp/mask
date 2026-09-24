package io.sqlmask.auth;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam of the simple auth mode. Implementations: JDBC (the monolith
 * default, table {@code auth_user}) and in-memory (tests).
 */
public interface SimpleUserStore {

  /** Creates the user; a duplicate username is an {@link AuthException} CONFIG error. */
  SimpleUser create(SimpleUser user);

  /** Replaces the stored user (username immutable). */
  SimpleUser update(SimpleUser user);

  Optional<SimpleUser> find(String username);

  List<SimpleUser> list();

  void delete(String username);
}
