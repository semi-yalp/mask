package io.sqlmask.common.web;

import io.sqlmask.audit.AuditEvents;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unified coverage for the one API-key gate. Supersedes the four per-service
 * filter test classes (PSRV-KY-001 cases included): services now only own
 * their wiring choice (fail-open vs fail-closed and URL patterns), which
 * their endpoint tests assert in passing.
 */
class ApiKeyFilterTest {

  /** MockFilterChain 是一次性的,每个请求独立建链。 */
  private MockHttpServletResponse run(ApiKeyFilter filter, String path, String key)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  // ---- fail-closed (mask-metadata, mask-query) ----

  @Test
  void failClosedUnconfiguredKeyRejectsEverything() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.failClosed(null);
    assertEquals(401, run(filter, "/api/instances", null).getStatus());
    assertEquals(401, run(filter, "/api/instances", "anything").getStatus());
  }

  @Test
  void failClosedBlankKeyRejectsEverything() throws Exception {
    assertEquals(401, run(ApiKeyFilter.failClosed("   "), "/api/v1/query", null).getStatus());
  }

  @Test
  void failClosedConfiguredKeyAcceptsMatchAndRejectsRest() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.failClosed("secret");
    assertEquals(200, run(filter, "/api/v1/query", "secret").getStatus());
    assertEquals(401, run(filter, "/api/v1/query", "wrong").getStatus());
    assertEquals(401, run(filter, "/api/v1/query", null).getStatus());
  }

  @Test
  void blankHeaderBehavesLikeMissingHeader() throws Exception {
    assertEquals(401, run(ApiKeyFilter.failClosed("secret"), "/api/x", "").getStatus());
  }

  @Test
  void nonAsciiKeysCompareByUtf8Bytes() throws Exception {
    // PSRV-KY-001 regression pin: comparison must be byte-wise UTF-8, not
    // char-wise, so no code-point confusion can make a wrong key pass
    String key = "密码密钥-2026";
    ApiKeyFilter filter = ApiKeyFilter.failClosed(key);
    assertEquals(200, run(filter, "/api/x", key).getStatus());
    assertEquals(401, run(filter, "/api/x", "密码密钥-2027").getStatus());
  }

  // ---- fail-open (mask-core surfaces) ----

  @Test
  void failOpenUnconfiguredKeyLetsEverythingThrough() throws Exception {
    assertEquals(200, run(ApiKeyFilter.failOpen(null), "/api/audit/events", null).getStatus());
  }

  @Test
  void failOpenConfiguredKeyStillEnforced() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.failOpen("rewrite-key");
    assertEquals(200, run(filter, "/api/rewrite/instances/pg", "rewrite-key").getStatus());
    assertEquals(401, run(filter, "/api/rewrite/instances/pg", "other").getStatus());
  }

  // ---- surfaces (mask-policy-server) ----

  @Test
  void surfacesRoutePathToItsOwnKey() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.surfaces(
        new ApiKeyFilter.Surface("/api/instances", "admin-key"),
        new ApiKeyFilter.Surface("/api/effective", "data-key"));
    assertEquals(200, run(filter, "/api/instances", "admin-key").getStatus());
    assertEquals(200, run(filter, "/api/instances/pg/udfs", "admin-key").getStatus());
    assertEquals(200, run(filter, "/api/effective", "data-key").getStatus());
    assertEquals(200, run(filter, "/api/effective/pg", "data-key").getStatus());
    // wrong surface key
    assertEquals(401, run(filter, "/api/instances", "data-key").getStatus());
    assertEquals(401, run(filter, "/api/effective/pg", "admin-key").getStatus());
    // unmanaged path passes without any key
    assertEquals(200, run(filter, "/api/rewrite", null).getStatus());
  }

  @Test
  void surfacePrefixDoesNotSwallowSiblingPaths() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.surfaces(
        new ApiKeyFilter.Surface("/api/instances", "admin-key"));
    // /api/instances-x is NOT the /api/instances surface: no key demanded
    assertEquals(200, run(filter, "/api/instances-x", null).getStatus());
  }

  @Test
  void blankSurfaceKeyMeansOpenSurface() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.surfaces(
        new ApiKeyFilter.Surface("/api/instances", "  "),
        new ApiKeyFilter.Surface("/api/effective", "data-key"));
    assertEquals(200, run(filter, "/api/instances", null).getStatus());
    assertEquals(401, run(filter, "/api/effective", null).getStatus());
  }

  // ---- cross-cutting contract ----

  @Test
  void acceptedRequestIsMarkedApiKeyForAudit() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query");
    request.setServletPath("/api/v1/query");
    request.addHeader("X-Api-Key", "secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ApiKeyFilter.failClosed("secret").doFilter(request, response, new MockFilterChain());
    assertEquals(AuditEvents.AUTH_KIND_API_KEY,
        request.getAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE));
  }

  @Test
  void rejectedRequestIsNotMarked() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query");
    request.setServletPath("/api/v1/query");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ApiKeyFilter.failClosed("secret").doFilter(request, response, new MockFilterChain());
    assertNull(request.getAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE));
  }

  @Test
  void unauthorizedBodyHasCodeMessageDetailsShape() throws Exception {
    MockHttpServletResponse response =
        run(ApiKeyFilter.failClosed("secret"), "/api/v1/query", "nope");
    assertEquals(401, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals(
        "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}",
        response.getContentAsString());
  }

  @Test
  void wrongLengthKeyIsRejected() throws Exception {
    ApiKeyFilter filter = ApiKeyFilter.failClosed("secret");
    assertEquals(401, run(filter, "/api/x", "s").getStatus());
    assertEquals(401, run(filter, "/api/x", "secret-with-suffix").getStatus());
  }

  @Test
  void chainReachedMeansRequestPassed() throws Exception {
    MockFilterChain passed = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
    request.setServletPath("/api/x");
    request.addHeader("X-Api-Key", "secret");
    ApiKeyFilter.failClosed("secret").doFilter(request, new MockHttpServletResponse(), passed);
    assertEquals(request, passed.getRequest());
    assertTrue(passed.getResponse() instanceof HttpServletResponse);
  }

  // ---- bearer 豁免（mask-auth 控制台用户）----

  /**
   * BearerAuthFilter 在 order 0 验签后打的 {@code auth.bearer} 标记应豁免
   * key 门禁（含 fail-closed 的未配 key 面），并改标 authKind=LDAP。
   */
  @Test
  void bearerAuthenticatedRequestBypassesEvenFailClosedUnconfiguredGate() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
    request.setServletPath("/api/x");
    // failClosed(null)：任何 key 都过不了，唯有 bearer 标记放行
    request.setAttribute("auth.bearer", Boolean.TRUE);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    ApiKeyFilter.failClosed(null).doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals(request, chain.getRequest());
    assertEquals(AuditEvents.AUTH_KIND_LDAP,
        request.getAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE));
  }

  @Test
  void bearerAuthorizationHeaderAloneDoesNotBypass() throws Exception {
    // 只有 Authorization 头而没有 BearerAuthFilter 打的标记，不构成豁免：
    // fail-closed 未配 key 照拒、配 key 的面错 key 照拒
    assertEquals(401, run(ApiKeyFilter.failClosed(null), "/api/x", null).getStatus());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
    request.setServletPath("/api/x");
    request.addHeader("Authorization", "Bearer whatever");
    MockHttpServletResponse response = new MockHttpServletResponse();
    ApiKeyFilter.failClosed("secret").doFilter(request, response, new MockFilterChain());
    assertEquals(401, response.getStatus());
  }
}
