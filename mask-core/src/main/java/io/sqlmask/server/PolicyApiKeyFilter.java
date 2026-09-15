package io.sqlmask.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Static API key gate over the policy service surfaces: the admin key guards
 * /api/instances/**, the data key guards /api/effective/**. An unconfigured
 * key leaves its surface open (this app also serves a local browser UI);
 * a configured key rejects every request without a matching X-Api-Key.
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
    String required = requiredKey(request.getRequestURI());
    if (required == null) {
      chain.doFilter(req, res);
      return;
    }
    if (!required.equals(request.getHeader("X-Api-Key"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    chain.doFilter(req, res);
  }

  /** Null when the path is unmanaged or its key is unconfigured (open). */
  private String requiredKey(String path) {
    if (path.startsWith("/api/instances")) {
      return adminKey == null || adminKey.isBlank() ? null : adminKey;
    }
    if (path.startsWith("/api/effective")) {
      return dataKey == null || dataKey.isBlank() ? null : dataKey;
    }
    return null;
  }
}
