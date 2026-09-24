package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The manual cache-drop endpoint is an admin action: fail-closed gate, so a
 * service with no SQLMASK_ADMIN_API_KEY rejects the request instead of
 * letting anyone flush the instance-mode caches.
 */
@SpringBootTest(properties = "SQLMASK_ADMIN_API_KEY=admin-secret")
@AutoConfigureMockMvc
class AdminCacheRefreshGateTest {

  @Autowired
  MockMvc mockMvc;

  @Test
  void refreshWithoutKeyIsRejected() throws Exception {
    mockMvc.perform(post("/admin/cache/refresh").servletPath("/admin/cache/refresh"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshWithWrongKeyIsRejected() throws Exception {
    mockMvc.perform(post("/admin/cache/refresh").servletPath("/admin/cache/refresh")
            .header("X-Api-Key", "nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshWithAdminKeyRunsAndAcceptsEmptyBody() throws Exception {
    mockMvc.perform(post("/admin/cache/refresh").servletPath("/admin/cache/refresh")
            .header("X-Api-Key", "admin-secret"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(0)); // empty cache in this context
  }
}