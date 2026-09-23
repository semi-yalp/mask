package io.sqlmask.metaserver.config;

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
 * Static API key gate over /api/*. Fail-closed: an unconfigured server key
 * rejects every request rather than opening the service.
 */
public class ApiKeyFilter implements Filter {

  private final String expectedKey;

  public ApiKeyFilter(String expectedKey) {
    this.expectedKey = expectedKey;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    // a verified bearer token (console user) satisfies this gate as well
    if (io.sqlmask.auth.AuthTokens.bearerAuthenticated(request)) {
      chain.doFilter(req, res);
      return;
    }
    String provided = request.getHeader("X-Api-Key");
    if (expectedKey == null || expectedKey.isBlank() || !keyMatches(expectedKey, provided)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    request.setAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE,
        io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY);
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
