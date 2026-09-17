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
 * Final-review C1: {@code audit.enabled=false} must not brick startup. The ES
 * search client bean is absent in that mode, so the query endpoint degrades to
 * the same 502 {@code AUDIT_SEARCH_UNAVAILABLE} as an ES outage — the
 * application context itself still loads.
 */
@SpringBootTest(properties = "audit.enabled=false")
@AutoConfigureMockMvc
class AuditDisabledEndpointTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void contextStartsWithAuditDisabledAndEventsReturn502() throws Exception {
    mvc.perform(get("/api/audit/events"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AUDIT_SEARCH_UNAVAILABLE"));
  }
}
