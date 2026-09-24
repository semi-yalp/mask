package io.sqlmask.riskserver.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Single shared API-key gate for /api/risk/**. Unset key = open access with a
 * one-time warning (demo/operator surface, matching the policy server's admin
 * plane semantics); a configured key is compared in constant time.
 */
public final class RiskApiFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(RiskApiFilter.class);

  private final String apiKey;
  private volatile boolean warnedOpen;

  public RiskApiFilter(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (apiKey.isEmpty()) {
      if (!warnedOpen) {
        warnedOpen = true;
        log.warn("risk: RISK_API_KEY not configured - /api/risk/** is OPEN (demo default; "
            + "set the key to gate the console + ingest API)");
      }
      chain.doFilter(request, response);
      return;
    }
    HttpServletRequest http = (HttpServletRequest) request;
    // a verified bearer token (the mask-auth console user, stamped by
    // BearerAuthFilter at a lower order) satisfies this gate as well. Literal
    // attribute contract — not a compile-time reference to io.sqlmask.auth —
    // so this filter keeps working even without mask-auth on the classpath.
    if (Boolean.TRUE.equals(http.getAttribute("auth.bearer"))) {
      chain.doFilter(request, response);
      return;
    }
    HttpServletResponse res = (HttpServletResponse) response;
    String provided = http.getHeader("X-Api-Key");
    boolean ok = provided != null
        && java.security.MessageDigest.isEqual(
            apiKey.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    if (!ok) {
      res.setStatus(401);
      res.setContentType("application/json");
      res.setCharacterEncoding("UTF-8");
      res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid X-Api-Key\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
