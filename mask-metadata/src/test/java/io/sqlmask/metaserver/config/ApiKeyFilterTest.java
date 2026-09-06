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

  private MockHttpServletResponse run(ApiKeyFilter filter, String key) throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void rejectsMissingKey() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("secret"), null);
    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
  }

  @Test
  void rejectsWrongKey() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "wrong");
    assertEquals(401, response.getStatus());
  }

  @Test
  void rejectsWhenServerKeyUnconfigured() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter(null), "anything");
    assertEquals(401, response.getStatus());
  }

  @Test
  void passesMatchingKey() throws Exception {
    MockHttpServletResponse response = run(new ApiKeyFilter("secret"), "secret");
    assertEquals(200, response.getStatus());
  }
}
