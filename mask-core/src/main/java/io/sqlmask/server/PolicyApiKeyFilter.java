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
 * Static API key gate over the policy service surfaces: the admin key guards
 * /api/instances/** and /api/audit/**, the data key guards /api/effective/**.
 * An unconfigured key leaves its surface open (this app also serves a local
 * browser UI); a configured key rejects every request without a matching
 * X-Api-Key. On a successful key check the request is marked with
 * {@code audit.authKind=API_KEY} for the audit recorder.
 *
 * <p>Path checks use {@link HttpServletRequest#getServletPath()}: the decoded
 * path without the context path. The container matches the filter's URL
 * patterns against that same decoded form, so the gate and the mapping can
 * never disagree — a raw {@code getRequestURI()} check would let an encoded
 * path ({@code /api/%69nstances}) or a context-path deployment slip through
 * the gate while still routing to the controllers.
 */
public final class PolicyApiKeyFilter implements Filter {

  private final String adminKey;
  private final String dataKey;

  public PolicyApiKeyFilter(String adminKey, String dataKey) {
    this.adminKey = adminKey;
    this.dataKey = dataKey;
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
    String required = requiredKey(request.getServletPath());
    if (required == null) {
      chain.doFilter(req, res);
      return;
    }
    if (!keyMatches(required, request.getHeader("X-Api-Key"))) {
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

  /** Null when the path is unmanaged or its key is unconfigured (open). */
  private String requiredKey(String path) {
    if (path.equals("/api/instances") || path.startsWith("/api/instances/")) {
      return adminKey == null || adminKey.isBlank() ? null : adminKey;
    }
    if (path.equals("/api/effective") || path.startsWith("/api/effective/")) {
      return dataKey == null || dataKey.isBlank() ? null : dataKey;
    }
    if (path.equals("/api/audit") || path.startsWith("/api/audit/")) {
      return adminKey == null || adminKey.isBlank() ? null : adminKey;
    }
    return null;
  }
}
