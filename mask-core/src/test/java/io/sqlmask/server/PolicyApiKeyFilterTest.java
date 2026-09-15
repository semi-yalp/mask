package io.sqlmask.server;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyApiKeyFilterTest {

  private MockHttpServletResponse run(PolicyApiKeyFilter filter, String path, String key)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
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
}
