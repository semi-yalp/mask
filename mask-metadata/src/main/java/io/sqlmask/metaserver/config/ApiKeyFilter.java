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
 * Static API key gate over /api/*, split by plane: the admin key guards
 * /api/instances/** (instance CRUD + live collection), the data key guards
 * /api/metadata/** (structure reads consumed by the policy and query
 * services). A plane whose effective key is unconfigured rejects every
 * request on that plane (fail-closed). Comparison is constant-time so the
 * check does not leak the key byte by byte.
 *
 * <p>Path checks use {@link HttpServletRequest#getServletPath()}: the decoded
 * path without the context path, the same form the container matches filter
 * URL patterns against.
 */
public class ApiKeyFilter implements Filter {

  private final String adminKey;
  private final String dataKey;

  public ApiKeyFilter(String adminKey, String dataKey) {
    this.adminKey = adminKey;
    this.dataKey = dataKey;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    String required = requiredKey(request.getServletPath());
    if (required == null || !keyMatches(required, request.getHeader("X-Api-Key"))) {
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

  private static boolean keyMatches(String expected, String provided) {
    if (provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
  }

  /** The data plane is /api/metadata/**, everything else under /api/* is admin. */
  private String requiredKey(String path) {
    if (path.equals("/api/metadata") || path.startsWith("/api/metadata/")) {
      return dataKey == null || dataKey.isBlank() ? null : dataKey;
    }
    return adminKey == null || adminKey.isBlank() ? null : adminKey;
  }
}
