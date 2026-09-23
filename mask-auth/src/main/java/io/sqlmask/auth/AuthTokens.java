package io.sqlmask.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-attribute accessors shared by the bearer gate, the API-key filters
 * (which honour an already-authenticated bearer) and the data-plane
 * controllers (which derive the policy subject from the verified identity
 * instead of caller-asserted parameters).
 */
public final class AuthTokens {

  /** Request attribute carrying the verified {@link AuthPrincipal}. */
  public static final String PRINCIPAL_ATTRIBUTE = "auth.principal";

  /** Marker the per-service API-key filters check to skip their key test. */
  public static final String BEARER_AUTHENTICATED_ATTRIBUTE = "auth.bearer";

  /**
   * Same attribute name and value contract as
   * {@code io.sqlmask.audit.AuditEvents#AUTH_KIND_ATTRIBUTE} — duplicated as a
   * literal so mask-auth stays independent of the mask-audit module.
   */
  public static final String AUTH_KIND_ATTRIBUTE = "audit.authKind";
  public static final String AUTH_KIND_LDAP = "LDAP";

  /** Request attributes the audit recorders read the identity from. */
  public static final String USER_ATTRIBUTE = "audit.user";
  public static final String GROUPS_ATTRIBUTE = "audit.groups";

  private AuthTokens() {
  }

  /** The verified bearer identity, or {@code null} when the request is not bearer-authenticated. */
  public static AuthPrincipal principal(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    Object value = request.getAttribute(PRINCIPAL_ATTRIBUTE);
    return value instanceof AuthPrincipal principal ? principal : null;
  }

  /** True when a bearer token was verified earlier in the filter chain. */
  public static boolean bearerAuthenticated(HttpServletRequest request) {
    return request != null
        && Boolean.TRUE.equals(request.getAttribute(BEARER_AUTHENTICATED_ATTRIBUTE));
  }
}
