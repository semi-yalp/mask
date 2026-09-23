package io.sqlmask.server;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyApiKeyFilterTest {

  /**
   * Mirrors a real container: the servlet path is the URL-decoded request URI
   * without the context path, and that is what the container matches filter
   * URL patterns against.
   */
  private MockHttpServletResponse run(PolicyApiKeyFilter filter, String path, String key)
      throws ServletException, IOException {
    return run(filter, path, path, key);
  }

  private MockHttpServletResponse run(
      PolicyApiKeyFilter filter, String requestUri, String servletPath, String key)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setServletPath(servletPath);
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void unconfiguredKeysPassEverything() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter(null, null);
    assertEquals(200, run(filter, "/api/instances", "whatever").getStatus());
    assertEquals(200, run(filter, "/api/effective/pg_prod", null).getStatus());
  }

  @Test
  void adminAndDataKeysAreSeparate() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/instances", "data-secret").getStatus());
    assertEquals(200, run(filter, "/api/instances", "admin-secret").getStatus());
    assertEquals(401, run(filter, "/api/effective/pg_prod", "admin-secret").getStatus());
    assertEquals(200, run(filter, "/api/effective/pg_prod", "data-secret").getStatus());
  }

  @Test
  void unauthenticatedShapeMatchesMetaserver() throws Exception {
    MockHttpServletResponse response = run(
        new PolicyApiKeyFilter("admin-secret", null), "/api/instances/pg_prod/udfs", null);
    assertEquals(401, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\","
        + "\"details\":[]}", response.getContentAsString());
  }

  @Test
  void otherPathsPassEvenWhenConfigured() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(200, run(filter, "/api/rewrite", null).getStatus());
    assertEquals(200, run(filter, "/index.html", null).getStatus());
  }

  @Test
  void auditSurfaceRequiresAdminKeyAndMarksAuthKind() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/audit/events", "data-secret").getStatus());
    assertEquals(401, run(filter, "/api/audit/events", null).getStatus());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    request.addHeader("X-Api-Key", "admin-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    java.util.concurrent.atomic.AtomicBoolean chainReached = new java.util.concurrent.atomic.AtomicBoolean();
    filter.doFilter(request, response, (req, res) -> chainReached.set(true));
    assertEquals(200, response.getStatus());
    assertTrue(chainReached.get());
    assertEquals(io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY,
        request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
  }

  @Test
  void openManagedPathStaysAnonymous() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter(null, null);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertNull(request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
  }

  @Test
  void metadataPullSurfaceRequiresAdminKey() throws Exception {
    // /api/metadata/pull 以调用方凭证连接任意库：必须归入 admin 面
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/metadata/pull", "data-secret").getStatus());
    assertEquals(401, run(filter, "/api/metadata/pull", null).getStatus());
    assertEquals(200, run(filter, "/api/metadata/pull", "admin-secret").getStatus());
  }

  @Test
  void segmentBoundaryIsEnforced() throws Exception {
    // a prefix must not unlock a sibling surface (/api/instances-evil, /api/effectiveX)
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(200, run(filter, "/api/instances-evil", null).getStatus());
    assertEquals(200, run(filter, "/api/effectiveX", null).getStatus());
  }

  @Test
  void encodedUriCannotBypassTheGate() throws Exception {
    // the container decodes /api/%69nstances to /api/instances for filter
    // mapping and controller routing; the gate must key off the decoded form
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/%69nstances/pg_prod", "/api/instances/pg_prod", null)
        .getStatus());
    assertEquals(200, run(filter, "/api/%69nstances/pg_prod", "/api/instances/pg_prod",
        "admin-secret").getStatus());
  }

  @Test
  void contextPathDeploymentIsStillGuarded() throws Exception {
    // under server.servlet.context-path=/app the request URI carries the
    // prefix; the servlet path the container routes on does not
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/app/api/instances/pg_prod", "/api/instances/pg_prod", null)
        .getStatus());
    assertEquals(200, run(filter, "/app/api/instances/pg_prod", "/api/instances/pg_prod",
        "admin-secret").getStatus());
    assertEquals(401, run(filter, "/app/api/effective/pg_prod", "/api/effective/pg_prod", null)
        .getStatus());
  }
}
