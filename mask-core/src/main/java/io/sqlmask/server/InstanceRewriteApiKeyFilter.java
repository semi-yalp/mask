package io.sqlmask.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional API-key gate over {@code /api/rewrite/instances/*}. Unconfigured =
 * open (the inline rewrite surface stays key-free, so the query data plane
 * ships usable by default); configured = enforced with the same fail-closed
 * JSON error shape as {@link PolicyApiKeyFilter}. The container scopes this
 * filter to the URL pattern, so — unlike the two-surface policy gate — no
 * path check is needed here.
 */
public final class InstanceRewriteApiKeyFilter implements Filter {

  private final String expectedKey;

  public InstanceRewriteApiKeyFilter(String expectedKey) {
    this.expectedKey = expectedKey;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (expectedKey == null || expectedKey.isBlank()) {
      chain.doFilter(req, res);
      return;
    }
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    // a verified bearer token (console user) satisfies this gate as well
    if (io.sqlmask.auth.AuthTokens.bearerAuthenticated(request)) {
      chain.doFilter(req, res);
      return;
    }
    if (!keyMatches(expectedKey, request.getHeader("X-Api-Key"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    chain.doFilter(req, res);
  }

  /** Constant-time comparison so the check does not leak the key byte by byte. */
  private static boolean keyMatches(String expected, String provided) {
    if (provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
  }
}
