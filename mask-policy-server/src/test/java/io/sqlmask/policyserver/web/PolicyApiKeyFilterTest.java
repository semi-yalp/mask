package io.sqlmask.policyserver.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
  void successfulKeyCheckMarksAuthKind() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    request.setServletPath("/api/instances");
    request.addHeader("X-Api-Key", "admin-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertEquals(200, response.getStatus());
    assertEquals(io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY,
        request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
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
  void auditIsNotAManagedSurfaceHere() throws Exception {
    // audit query lives in mask-core; policy-server maps no gate onto
    // /api/audit — the path simply passes through unmanaged (the dispatcher
    // answers 404 behind the filter).
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(200, run(filter, "/api/audit/events", null).getStatus());
    assertEquals(200, run(filter, "/api/audit/events", "admin-secret").getStatus());
    assertEquals(200, run(filter, "/api/audit/events", "data-secret").getStatus());
  }

  @Test
  void openManagedPathStaysAnonymous() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter(null, null);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    request.setServletPath("/api/instances");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertNull(request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
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

  @Test
  void blankHeaderIsRejectedWhenConfigured() throws Exception {
    // header present but empty must behave like a missing header
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/instances", "").getStatus());
    assertEquals(401, run(filter, "/api/effective/pg_prod", "").getStatus());
  }

  @Test
  void nonAsciiKeysCompareByUtf8Bytes() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("管理密钥-κλé🔑", "数据密钥");
    assertEquals(200, run(filter, "/api/instances", "管理密钥-κλé🔑").getStatus());
    assertEquals(200, run(filter, "/api/effective/pg_prod", "数据密钥").getStatus());
    assertEquals(401, run(filter, "/api/instances", "管理密钥").getStatus());
  }
}
