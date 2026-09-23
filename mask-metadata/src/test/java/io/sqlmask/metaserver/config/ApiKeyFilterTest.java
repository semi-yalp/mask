package io.sqlmask.metaserver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

class ApiKeyFilterTest {

  private final MockFilterChain chain = new MockFilterChain();

  private MockHttpServletResponse run(ApiKeyFilter filter, String path, String key)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void adminSurfaceRejectsMissingAndWrongKeys() throws Exception {
    ApiKeyFilter filter = new ApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/instances", null).getStatus());
    assertEquals(401, run(filter, "/api/instances", "wrong").getStatus());
    assertEquals(401, run(filter, "/api/instances/pg_prod/collect", "data-secret").getStatus());
    assertEquals(200, run(filter, "/api/instances", "admin-secret").getStatus());
  }

  @Test
  void dataSurfaceIsKeyedByTheDataKeyOnly() throws Exception {
    ApiKeyFilter filter = new ApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/metadata/instances/pg_prod", null).getStatus());
    assertEquals(401, run(filter, "/api/metadata/instances/pg_prod", "admin-secret").getStatus());
    assertEquals(200, run(filter, "/api/metadata/instances/pg_prod", "data-secret").getStatus());
  }

  @Test
  void unconfiguredPlaneFailsClosed() throws Exception {
    // 任一面 key 缺失时该面全 401，绝不放行
    assertEquals(401, run(new ApiKeyFilter(null, "data-secret"), "/api/instances", null).getStatus());
    assertEquals(401, run(new ApiKeyFilter(null, "data-secret"), "/api/instances", "x").getStatus());
    assertEquals(401, run(new ApiKeyFilter("admin-secret", null), "/api/metadata/x", null).getStatus());
    assertEquals(401, run(new ApiKeyFilter(null, null), "/api/instances", null).getStatus());
  }

  @Test
  void unauthenticatedShapeMatchesSiblingServices() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("admin-secret", null),
        "/api/metadata/x", null);
    assertEquals(401, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\","
        + "\"details\":[]}", response.getContentAsString());
  }

  @Test
  void validKeyMarksAuthKindAttribute() throws Exception {
    ApiKeyFilter filter = new ApiKeyFilter("admin-secret", "data-secret");
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
  void contextPathDeploymentStaysGuarded() throws Exception {
    // context-path 部署下 requestURI 带 /app 前缀，servletPath 不带——门禁按后者
    ApiKeyFilter filter = new ApiKeyFilter("admin-secret", "data-secret");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/instances");
    request.setServletPath("/api/instances");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
  }
}
