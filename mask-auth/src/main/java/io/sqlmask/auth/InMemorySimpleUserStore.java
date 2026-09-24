package io.sqlmask.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link SimpleUserStore} for tests and CLI-only runs. */
public final class InMemorySimpleUserStore implements SimpleUserStore {

  private final Map<String, SimpleUser> users = new LinkedHashMap<>();

  @Override
  public synchronized SimpleUser create(SimpleUser user) {
    if (users.containsKey(user.username())) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "user '" + user.username() + "' already exists");
    }
    users.put(user.username(), user);
    return user;
  }

  @Override
  public synchronized SimpleUser update(SimpleUser user) {
    if (!users.containsKey(user.username())) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS,
          "user '" + user.username() + "' does not exist");
    }
    users.put(user.username(), user);
    return user;
  }

  @Override
  public synchronized Optional<SimpleUser> find(String username) {
    return Optional.ofNullable(users.get(username));
  }

  @Override
  public synchronized List<SimpleUser> list() {
    return new ArrayList<>(users.values());
  }

  @Override
  public synchronized void delete(String username) {
    users.remove(username);
  }
}
