package io.sqlmask.riskserver.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Custom-rule creation over HTTP: the console's "enabled" switch must be
 * honored (a regression made it always-true for newly created rules).
 * Booting the full context also proves the create path works end-to-end.
 */
@SpringBootTest(properties = {
    "risk.store.persistence-path=", // keep the test out of data/
    "risk.demo.seed-on-start=false"})
@AutoConfigureMockMvc
class RuleCreateWebTest {

  @Autowired
  MockMvc mockMvc;

  private static final String DISABLED_BODY = """
      {"name": "svc probe", "description": "d", "category": "CUSTOM",
       "severity": "HIGH", "enabled": false,
       "conditions": [{"field": "user", "op": "startswith", "value": "svc_"}],
       "window": {"seconds": 120, "count": 8}}
      """;

  private static final String ENABLED_BODY = """
      {"name": "svc probe 2", "description": "d", "category": "CUSTOM",
       "severity": "HIGH", "enabled": true,
       "conditions": [{"field": "user", "op": "startswith", "value": "svc_"}],
       "window": {"seconds": 120, "count": 8}}
      """;

  @Test
  void createWithEnabledFalseStaysDisabled() throws Exception {
    mockMvc.perform(post("/api/risk/rules").servletPath("/api/risk/rules")
            .contentType(MediaType.APPLICATION_JSON).content(DISABLED_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  void createWithEnabledTrueIsEnabled() throws Exception {
    mockMvc.perform(post("/api/risk/rules").servletPath("/api/risk/rules")
            .contentType(MediaType.APPLICATION_JSON).content(ENABLED_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
  }
}