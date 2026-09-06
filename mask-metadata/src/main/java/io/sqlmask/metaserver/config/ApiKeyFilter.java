package io.sqlmask.metaserver.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

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
    String provided = request.getHeader("X-Api-Key");
    if (expectedKey == null || expectedKey.isBlank() || !expectedKey.equals(provided)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    chain.doFilter(req, res);
  }
}
