package io.sqlmask.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Instance mode error-code contract: when the (stubbed) policy service
 * answers 404 / 401 / 500 the rewrite endpoint must map them to the
 * documented 400 + code triple (POLICY_INSTANCE_NOT_FOUND / CONFIG_ERROR /
 * POLICY_SERVICE_UNAVAILABLE) and record exactly one FAILURE audit event
 * carrying the same code. Gap §4.1 item 2 (P0).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RewriteInstanceModeFailureTest {

  private static final String EFFECTIVE_BODY = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"id","type":"bigint"},
           {"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"mask_phone"}],
         "policies":{"mask_phone":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private static HttpServer stub;
  private static final AtomicReference<Integer> stubStatus = new AtomicReference<>(200);

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/api/effective/pg_prod", exchange -> {
      byte[] body = EFFECTIVE_BODY.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(stubStatus.get(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @DynamicPropertySource
  static void policyService(DynamicPropertyRegistry registry) {
    registry.add("policy.service.url", () -> "http://localhost:" + stub.getAddress().getPort());
    registry.add("policy.service.api-key", () -> "data-key-1");
  }

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private AuditRecorder recorder;

  private void rewriteAgainstStatus(int status, String expectedCode, String messageToken,
      String auditErrorCode) throws Exception {
    stubStatus.set(status);
    try {
      mvc.perform(post("/api/rewrite")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(Map.of(
                  "instance", "pg_prod", "sql", "SELECT id, phone FROM customer"))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(expectedCode))
          .andExpect(jsonPath("$.message", containsString(messageToken)));
    } finally {
      stubStatus.set(200); // leave the shared stub healthy for sibling tests
    }
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, org.mockito.Mockito.times(1)).record(captor.capture());
    AuditEvent event = captor.getValue();
    assertEquals(AuditEvent.REWRITE, event.eventType(), "failure must be a REWRITE event");
    assertEquals(AuditEvent.FAILURE, event.outcome(), "failure must be a FAILURE event");
    assertEquals(auditErrorCode, event.errorCode(),
        "audit errorCode must mirror the documented error code");
  }

  @Test
  void policyService404MapsToInstanceNotFound() throws Exception {
    rewriteAgainstStatus(404, "POLICY_INSTANCE_NOT_FOUND", "does not exist",
        "POLICY_INSTANCE_NOT_FOUND");
  }

  @Test
  void policyService401MapsToConfigError() throws Exception {
    rewriteAgainstStatus(401, "CONFIG_ERROR", "API key", "CONFIG_ERROR");
  }

  @Test
  void policyService500MapsToServiceUnavailable() throws Exception {
    rewriteAgainstStatus(500, "POLICY_SERVICE_UNAVAILABLE", "HTTP 500",
        "POLICY_SERVICE_UNAVAILABLE");
  }
}