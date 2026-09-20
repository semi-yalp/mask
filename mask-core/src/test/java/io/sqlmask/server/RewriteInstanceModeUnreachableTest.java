package io.sqlmask.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Instance mode with a configured-but-unreachable policy service (connection
 * refused): the rewrite endpoint must fail closed with 400 +
 * POLICY_SERVICE_UNAVAILABLE and one FAILURE audit event, never pass the
 * unmasked SQL through. Gap §4.1 item 2 (P0), connection-refused arm.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RewriteInstanceModeUnreachableTest {

  // port 1 cannot be bound by a listener: connect fails immediately
  private static final String DEAD_URL = "http://127.0.0.1:1";

  @DynamicPropertySource
  static void policyService(DynamicPropertyRegistry registry) {
    registry.add("policy.service.url", () -> DEAD_URL);
    registry.add("policy.service.api-key", () -> "data-key-1");
  }

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void unreachablePolicyServiceMapsToServiceUnavailable() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "sql", "SELECT id, phone FROM customer"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_SERVICE_UNAVAILABLE"))
        .andExpect(jsonPath("$.message", containsString("unreachable")));

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, org.mockito.Mockito.times(1)).record(captor.capture());
    assertEquals(AuditEvent.FAILURE, captor.getValue().outcome());
    assertEquals("POLICY_SERVICE_UNAVAILABLE", captor.getValue().errorCode());
  }
}