package io.sqlmask.server.auth;

import io.sqlmask.auth.AuthException;
import io.sqlmask.auth.Passwords;
import io.sqlmask.auth.Role;
import io.sqlmask.auth.SimpleUser;
import io.sqlmask.auth.SimpleUserStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Local user administration of the simple auth mode (ADMIN role required;
 * enforced by the bearer gate's {@code /api/auth/users} rule). Passwords are
 * write-only: never returned, stored as PBKDF2 hashes.
 */
@RestController
@RequestMapping("/api/auth/users")
public class UserAdminController {

  private final ObjectProvider<SimpleUserStore> users;

  public UserAdminController(ObjectProvider<SimpleUserStore> users) {
    this.users = users;
  }

  public record UserPayload(String username, String displayName, String role,
                            List<String> groups, String password, Boolean enabled) {
  }

  public record UserView(String username, String displayName, String role, List<String> groups,
                         boolean enabled) {
  }

  @GetMapping
  public List<UserView> list() {
    return store().list().stream().map(UserAdminController::toView).toList();
  }

  @GetMapping("/{username}")
  public UserView get(@PathVariable("username") String username) {
    return toView(find(username));
  }

  @PostMapping
  public UserView create(@RequestBody UserPayload payload) {
    if (payload == null || isBlank(payload.username()) || isBlank(payload.password())) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "username and password are required");
    }
    SimpleUser created = store().create(new SimpleUser(payload.username().trim(),
        blankTo(payload.displayName(), payload.username().trim()), parseRole(payload.role()),
        payload.groups(), Passwords.hash(payload.password()),
        payload.enabled() == null || payload.enabled()));
    return toView(created);
  }

  @PutMapping("/{username}")
  public UserView update(@PathVariable("username") String username,
                         @RequestBody UserPayload payload) {
    SimpleUser existing = find(username);
    String hash = payload != null && !isBlank(payload.password())
        ? Passwords.hash(payload.password())
        : existing.passwordHash();
    SimpleUser updated = store().update(new SimpleUser(existing.username(),
        payload != null && !isBlank(payload.displayName())
            ? payload.displayName() : existing.displayName(),
        payload != null && !isBlank(payload.role()) ? parseRole(payload.role()) : existing.role(),
        payload != null && payload.groups() != null ? payload.groups() : existing.groups(),
        hash,
        payload != null && payload.enabled() != null ? payload.enabled() : existing.enabled()));
    return toView(updated);
  }

  @DeleteMapping("/{username}")
  public ResponseEntity<Object> delete(@PathVariable("username") String username) {
    store().delete(username);
    return ResponseEntity.noContent().build();
  }

  private SimpleUserStore store() {
    SimpleUserStore store = users.getIfAvailable();
    if (store == null) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "user administration requires mask.auth.mode=simple");
    }
    return store;
  }

  private SimpleUser find(String username) {
    return store().find(username).orElseThrow(() -> new AuthException(
        AuthException.Code.INVALID_CREDENTIALS, "user '" + username + "' does not exist"));
  }

  private static Role parseRole(String role) {
    if (isBlank(role)) {
      return Role.USER;
    }
    try {
      return Role.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "unknown role '" + role + "' (USER, AUDITOR, ADMIN)");
    }
  }

  private static UserView toView(SimpleUser user) {
    return new UserView(user.username(), user.displayName(), user.role().name(),
        user.groups(), user.enabled());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankTo(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }
}
