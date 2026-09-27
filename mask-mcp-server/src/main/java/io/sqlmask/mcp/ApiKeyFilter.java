package io.sqlmask.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * X-Api-Key gate for the HTTP transport. Same principle as the admin cache
 * refresh endpoint: when no key is configured, every request is rejected.
 */
public final class ApiKeyFilter implements Filter {

  private final String expectedKey;

  public ApiKeyFilter(String expectedKey) {
    this.expectedKey = expectedKey;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    if (expectedKey == null || expectedKey.isBlank()
        || !expectedKey.equals(req.getHeader("X-Api-Key"))) {
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      res.setContentType("application/json");
      res.getWriter().write(McpErrors.json(
          new McpErrors.ApiError("UNAUTHORIZED", "missing or invalid X-Api-Key", List.of())));
      return;
    }
    chain.doFilter(request, response);
  }
}
