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
 * The manual cache-drop endpoint. The retired fail-closed API-key gate is
 * gone with the service split — in the monolith the endpoint is protected by
 * the (optional) central auth layer instead; the no-auth default keeps it
 * reachable, which is the deployment's explicit choice.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminCacheRefreshGateTest {

  @Autowired
  MockMvc mockMvc;

  @Test
  void refreshRunsAndAcceptsEmptyBody() throws Exception {
    mockMvc.perform(post("/admin/cache/refresh").servletPath("/admin/cache/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(0)); // empty cache in this context
  }
}
