package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code audit.enabled=false} the whole pipeline is a no-op (spec §9) and
 * no ES client exists — the query endpoint must neither break context startup
 * nor blow up: it answers 502 AUDIT_SEARCH_UNAVAILABLE.
 */
@SpringBootTest(properties = "audit.enabled=false")
@AutoConfigureMockMvc
class AuditDisabledEndpointTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void contextLoadsAndQueryAnswers502() throws Exception {
    mvc.perform(get("/api/audit/events"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AUDIT_SEARCH_UNAVAILABLE"));
  }
}