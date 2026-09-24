package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Instance mode without policy.service.url fails fast with a actionable error. */
@SpringBootTest
@AutoConfigureMockMvc
class RewriteInstanceModeUnconfiguredTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void instanceModeWithoutConfiguredUrlIsAConfigError() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"instance": "pg_prod", "sql": "SELECT id, phone FROM customer"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("POLICY_SERVICE_URL")));
  }
}
