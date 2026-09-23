package io.sqlmask.common.web;

import io.sqlmask.audit.AuditEvents;
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
import java.util.List;

/**
 * Unified static API-key gate for every service. Replaces the five copy-pasted
 * filters that used to diverge between fail-open and fail-closed semantics:
 *
 * <ul>
 *   <li>{@link #failClosed(String)} — unconfigured key rejects every request
 *       (mask-metadata, mask-query: the services that export real data);</li>
 *   <li>{@link #failOpen(String)} — unconfigured key leaves the surface open
 *       (mask-core's audit-query and instance-rewrite surfaces, whose URL
 *       patterns already scope the filter);</li>
 *   <li>{@link #surfaces(Surface, Surface[])} — path-prefix routing to
 *       different keys per surface, each individually fail-open
 *       (mask-policy-server: admin key on /api/instances, data key on
 *       /api/effective).</li>
 * </ul>
 *
 * <p>Path checks use {@link HttpServletRequest#getServletPath()}: the decoded
 * path without the context path. The container matches the filter's URL
 * patterns against that same decoded form, so the gate and the mapping can
 * never disagree — a raw {@code getRequestURI()} check would let an encoded
 * path ({@code /api/%69nstances}) or a context-path deployment slip through
 * the gate while still routing to the controllers.
 *
 * <p>On a successful key check the request is marked with
 * {@code audit.authKind=API_KEY} for the audit recorder.
 */
public final class ApiKeyFilter implements Filter {

  /** A guarded path prefix and the key that unlocks it (blank = open). */
  public record Surface(String pathPrefix, String key) {
  }

  private static final String UNAUTHORIZED_BODY =
      "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}";

  /** Prefix that matches every path the registration can route here. */
  private static final String ANY_PATH = "/";

  /** Sentinel key meaning "no credential can pass" (fail-closed, unconfigured). */
  private static final String REJECT_ALL = new String(new char[0]);

  private final List<Surface> surfaces;
  private final boolean failClosedWhenUnconfigured;

  private ApiKeyFilter(boolean failClosedWhenUnconfigured, Surface... surfaces) {
    this.surfaces = List.of(surfaces);
    this.failClosedWhenUnconfigured = failClosedWhenUnconfigured;
  }

  /** Single-surface gate; an unconfigured (null/blank) key rejects everything. */
  public static ApiKeyFilter failClosed(String key) {
    return new ApiKeyFilter(true, new Surface(ANY_PATH, key));
  }

  /** Single-surface gate; an unconfigured (null/blank) key leaves it open. */
  public static ApiKeyFilter failOpen(String key) {
    return new ApiKeyFilter(false, new Surface(ANY_PATH, key));
  }

  /**
   * Multi-surface gate: the first surface whose prefix matches the servlet
   * path supplies the required key; no match passes through. Each surface is
   * individually fail-open (blank key = open surface).
   */
  public static ApiKeyFilter surfaces(Surface first, Surface... rest) {
    Surface[] all = new Surface[rest.length + 1];
    all[0] = first;
    System.arraycopy(rest, 0, all, 1, rest.length);
    return new ApiKeyFilter(false, all);
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    String required = requiredKey(request.getServletPath());
    if (required == null) {
      chain.doFilter(req, res);
      return;
    }
    if (!keyMatches(required, request.getHeader("X-Api-Key"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(UNAUTHORIZED_BODY);
      return;
    }
    request.setAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE, AuditEvents.AUTH_KIND_API_KEY);
    chain.doFilter(req, res);
  }

  /** Null when the request needs no key (unmanaged path or open surface). */
  private String requiredKey(String path) {
    for (Surface surface : surfaces) {
      if (matches(path, surface.pathPrefix())) {
        String key = surface.key();
        if (key == null || key.isBlank()) {
          return failClosedWhenUnconfigured ? REJECT_ALL : null;
        }
        return key;
      }
    }
    return null;
  }

  /** Exact or slash-delimited prefix, so {@code /api/instances} never matches {@code /api/instances-x}. */
  private static boolean matches(String path, String prefix) {
    if (prefix.equals(ANY_PATH)) {
      return true;
    }
    return path.equals(prefix) || path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/");
  }

  /** Constant-time comparison so the check does not leak the key byte by byte. */
  private static boolean keyMatches(String expected, String provided) {
    if (provided == null || expected == REJECT_ALL) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
  }
}
