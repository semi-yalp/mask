package io.sqlmask.audit;

import jakarta.servlet.http.HttpServletRequest;

/** Request-context extraction shared by all audit integration points. */
public final class AuditEvents {

  /** Set by the host service's API-key filter once a key check passes. */
  public static final String AUTH_KIND_ATTRIBUTE = "audit.authKind";
  public static final String AUTH_KIND_API_KEY = "API_KEY";
  public static final String AUTH_KIND_ANONYMOUS = "ANONYMOUS";

  private AuditEvents() {
  }

  public static String sourceIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }

  public static String authKind(HttpServletRequest request) {
    if (request == null) {
      return AUTH_KIND_ANONYMOUS;
    }
    Object marked = request.getAttribute(AUTH_KIND_ATTRIBUTE);
    return marked == null ? AUTH_KIND_ANONYMOUS : marked.toString();
  }
}
