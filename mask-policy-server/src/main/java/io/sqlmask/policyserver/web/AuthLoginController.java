package io.sqlmask.policyserver.web;

import io.sqlmask.auth.AuthException;
import io.sqlmask.auth.AuthPrincipal;
import io.sqlmask.auth.AuthTokenService;
import io.sqlmask.auth.AuthTokens;
import io.sqlmask.auth.LdapAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console login surface: LDAP username/password in, a signed bearer token
 * out. The token is the same one every console-facing service verifies (the
 * shared {@code MASK_AUTH_SECRET}), so login happens here once and the
 * identity travels with the request. {@code /api/auth/**} sits outside the
 * API-key filter's URL patterns, so login itself needs no key.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthLoginController {

  private final ObjectProvider<LdapAuthenticator> ldap;
  private final ObjectProvider<AuthTokenService> tokens;

  public AuthLoginController(ObjectProvider<LdapAuthenticator> ldap,
      ObjectProvider<AuthTokenService> tokens) {
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
    LdapAuthenticator authenticator = ldap.getIfAvailable();
    AuthTokenService tokenService = tokens.getIfAvailable();
    if (authenticator == null || tokenService == null) {
      return error(501, "LDAP_NOT_CONFIGURED",
          "login is disabled: set MASK_AUTH_SECRET and MASK_AUTH_LDAP_URL/MASK_AUTH_LDAP_BASE_DN");
    }
    try {
      AuthPrincipal principal = authenticator.authenticate(
          request.username().trim(), request.password().toCharArray());
      AuthTokenService.IssuedToken issued = tokenService.issue(principal, Instant.now());
      return ResponseEntity.ok(new LoginResponse(issued.token(),
          toLoginUser(principal), issued.expiresInSeconds()));
    } catch (AuthException e) {
      if (e.code() == AuthException.Code.INVALID_CREDENTIALS) {
        return error(401, "INVALID_CREDENTIALS", "invalid username or password");
      }
      // LDAP_UNAVAILABLE and any construction-time CONFIG_ERROR: fail closed
      return error(503, e.code().name(), e.getMessage());
    }
  }

  @GetMapping("/me")
  public ResponseEntity<Object> me(HttpServletRequest request) {
    AuthPrincipal principal = AuthTokens.principal(request);
    if (principal == null) {
      return error(401, "UNAUTHORIZED", "missing or invalid bearer token");
    }
    return ResponseEntity.ok(toLoginUser(principal));
  }

  /** Public: tells the console whether to force the login page or keep the API-key mode. */
  @GetMapping("/mode")
  public Map<String, Object> mode() {
    return Map.of("ldap", ldap.getIfAvailable() != null && tokens.getIfAvailable() != null);
  }

  private static LoginUser toLoginUser(AuthPrincipal principal) {
    return new LoginUser(principal.username(), principal.displayName(),
        principal.role().name(), principal.groups());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Repo-standard error body {code, message, details[]} with an explicit status. */
  private static ResponseEntity<Object> error(int status, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    body.put("details", List.of());
    return ResponseEntity.status(status).body(body);
  }
}
