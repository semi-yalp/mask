package io.sqlmask.server.auth;

import io.sqlmask.auth.AuthException;
import io.sqlmask.auth.AuthMode;
import io.sqlmask.auth.AuthPrincipal;
import io.sqlmask.auth.AuthTokenService;
import io.sqlmask.auth.AuthTokens;
import io.sqlmask.auth.LdapAuthenticator;
import io.sqlmask.auth.Passwords;
import io.sqlmask.auth.SimpleUser;
import io.sqlmask.auth.SimpleUserStore;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console login surface, one place for every mode: simple (local user
 * table), ldap (corporate directory) or none (login disabled; the console
 * detects this via {@code /api/auth/mode} and skips the login page). The
 * issued token is the same HS256 bearer every surface verifies.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthEndpoints {

  private static final Logger log = LoggerFactory.getLogger(AuthEndpoints.class);

  private final AuthMode mode;
  private final ObjectProvider<SimpleUserStore> users;
  private final ObjectProvider<LdapAuthenticator> ldap;
  private final ObjectProvider<AuthTokenService> tokens;

  public AuthEndpoints(AuthMode mode, ObjectProvider<SimpleUserStore> users,
                       ObjectProvider<LdapAuthenticator> ldap,
                       ObjectProvider<AuthTokenService> tokens) {
    this.mode = mode;
    this.users = users;
    this.ldap = ldap;
    this.tokens = tokens;
  }

  public record LoginRequest(String username, String password) {
  }

  public record LoginUser(String username, String displayName, String role, List<String> groups) {
  }

  public record LoginResponse(String token, LoginUser user, long expiresInSeconds) {
  }

  @PostMapping("/login")
  public ResponseEntity<Object> login(@RequestBody(required = false) LoginRequest request) {
    if (request == null || isBlank(request.username()) || request.password() == null
        || request.password().isEmpty()) {
      return error(400, "INVALID_REQUEST", "username and password are required");
    }
    AuthTokenService tokenService = tokens.getIfAvailable();
    if (tokenService == null) {
      return error(501, "AUTH_NOT_CONFIGURED",
          "login is disabled: set mask.auth.mode=simple or ldap (see docs/deployment.md)");
    }
    AuthPrincipal principal;
    try {
      principal = switch (mode) {
        case SIMPLE -> authenticateSimple(request);
        case LDAP -> authenticateLdap(request);
        case NONE -> null;
      };
    } catch (AuthException e) {
      if (e.code() == AuthException.Code.INVALID_CREDENTIALS) {
        return error(401, "INVALID_CREDENTIALS", "invalid username or password");
      }
      // LDAP_UNAVAILABLE and construction-time CONFIG_ERROR: fail closed
      return error(503, e.code().name(), e.getMessage());
    }
    if (principal == null) {
      return error(501, "AUTH_NOT_CONFIGURED",
          "login is disabled: set mask.auth.mode=simple or ldap (see docs/deployment.md)");
    }
    AuthTokenService.IssuedToken issued = tokenService.issue(principal, Instant.now());
    return ResponseEntity.ok(new LoginResponse(issued.token(),
        toLoginUser(principal), issued.expiresInSeconds()));
  }

  private AuthPrincipal authenticateSimple(LoginRequest request) {
    SimpleUserStore store = users.getIfAvailable();
    if (store == null) {
      log.warn("simple mode login attempted without a user store");
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "the local user store is not available");
    }
    SimpleUser user = store.find(request.username().trim()).orElse(null);
    // same message for unknown user and wrong password: no existence leak
    if (user == null || !user.enabled() || !Passwords.verify(user.passwordHash(),
        request.password())) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS,
          "invalid username or password");
    }
    return new AuthPrincipal(user.username(), user.displayName(), user.role(), user.groups());
  }

  private AuthPrincipal authenticateLdap(LoginRequest request) {
    LdapAuthenticator authenticator = ldap.getIfAvailable();
    if (authenticator == null) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "ldap mode requires MASK_AUTH_LDAP_URL and MASK_AUTH_LDAP_BASE_DN");
    }
    return authenticator.authenticate(request.username().trim(), request.password().toCharArray());
  }

  @GetMapping("/me")
  public ResponseEntity<Object> me(HttpServletRequest request) {
    AuthPrincipal principal = AuthTokens.principal(request);
    if (principal == null) {
      return error(401, "UNAUTHORIZED", "missing or invalid bearer token");
    }
    return ResponseEntity.ok(toLoginUser(principal));
  }

  /** Public: tells the console which auth posture to expect. The legacy
   * {@code ldap} boolean stays for older frontends. */
  @GetMapping("/mode")
  public Map<String, Object> mode() {
    boolean loginUsable = tokens.getIfAvailable() != null;
    return Map.of(
        "mode", mode.name().toLowerCase(),
        "login", loginUsable,
        "ldap", mode == AuthMode.LDAP && loginUsable);
  }

  private static LoginUser toLoginUser(AuthPrincipal principal) {
    return new LoginUser(principal.username(), principal.displayName(),
        principal.role().name(), principal.groups());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Repo-standard error body {code, message, details[]} with an explicit status. */
  static ResponseEntity<Object> error(int status, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("details", List.of());
    return ResponseEntity.status(status).body(body);
  }
}
