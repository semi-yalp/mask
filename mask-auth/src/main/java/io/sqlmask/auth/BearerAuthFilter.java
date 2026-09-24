package io.sqlmask.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Bearer-token gate registered at order 0 (ahead of every API-key filter) on
 * the console-facing surfaces. Contract:
 *
 * <ul>
 *   <li>no {@code Authorization: Bearer} header → pass through untouched; the
 *       existing API-key path (or an open surface) applies, so deployments
 *       without {@code MASK_AUTH_SECRET} behave exactly like the pre-LDAP
 *       build;</li>
 *   <li>valid token → stamp request attributes ({@code auth.principal},
 *       {@code auth.bearer}, {@code audit.authKind=LDAP}, {@code audit.user},
 *       {@code audit.groups}) so downstream API-key filters skip their key
 *       test and audit records carry the real identity; then enforce the
 *       configured {@link AuthRule}s — a role shortfall is 403;</li>
 *   <li>invalid/expired token → 401 immediately. No fallback to API keys:
 *       silently downgrading a presented credential would be an attack
 *       path;</li>
 *   <li>path covered by no rule → allowed once authenticated (rules add
 *       restrictions on top of the existing gates; ungated paths stay
 *       ungated).</li>
 * </ul>
 */
public final class BearerAuthFilter implements Filter {

  private final AuthTokenService tokens;
  private final List<AuthRule> rules;

  public BearerAuthFilter(AuthTokenService tokens, List<AuthRule> rules) {
    this.tokens = tokens;
    this.rules = List.copyOf(rules);
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      chain.doFilter(req, res);
      return;
    }
    AuthPrincipal principal;
    try {
      principal = tokens.verify(header.substring(7).trim(), Instant.now());
    } catch (AuthException e) {
      reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
          e.code() == AuthException.Code.EXPIRED_TOKEN ? "token expired" : "invalid token");
      return;
    }
    request.setAttribute(AuthTokens.PRINCIPAL_ATTRIBUTE, principal);
    request.setAttribute(AuthTokens.BEARER_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
    request.setAttribute(AuthTokens.AUTH_KIND_ATTRIBUTE, AuthTokens.AUTH_KIND_LDAP);
    request.setAttribute(AuthTokens.USER_ATTRIBUTE, principal.username());
    request.setAttribute(AuthTokens.GROUPS_ATTRIBUTE, String.join(",", principal.groups()));

    AuthRule violated = violatingRule(request.getServletPath(), request.getMethod(), principal.role());
    if (violated != null) {
      reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
          "role " + principal.role() + " is not allowed to " + request.getMethod() + " "
              + request.getServletPath());
      return;
    }
    chain.doFilter(req, res);
  }

  private AuthRule violatingRule(String path, String method, Role role) {
    for (AuthRule rule : rules) {
      if (rule.matches(path, method) && !role.atLeast(rule.minRole)) {
        return rule;
      }
    }
    return null;
  }

  private static void reject(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.getOutputStream().write(("{\"code\":\"" + code + "\",\"message\":\""
        + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"details\":[]}")
        .getBytes(StandardCharsets.UTF_8));
  }
}
