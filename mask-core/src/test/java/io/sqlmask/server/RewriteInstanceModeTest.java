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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Instance mode: the compiled effective config comes from the (stubbed)
 * policy service over HTTP; inline YAML is rejected in the same request.
 * Every request — success or validation failure — emits exactly one REWRITE
 * audit event.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RewriteInstanceModeTest {

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
  private static final AtomicReference<String> seenApiKey = new AtomicReference<>("");

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/api/effective/pg_prod", exchange -> {
      seenApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      byte[] body = EFFECTIVE_BODY.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
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

  @Test
  void rewritesWithCompiledConfigAndSendsConfiguredApiKey() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "sql", "SELECT id, phone FROM customer"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(true))
        .andExpect(jsonPath("$.rewrittenSql")
            .value(containsString("mask_phone(r.phone, 3, 4) AS phone")));
    assertThat(seenApiKey.get()).isEqualTo("data-key-1");
  }

  @Test
  void instanceModeIgnoresRequestDialectField() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "sql", "SELECT id, phone FROM customer",
                "dialect", "mysql"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rewrittenSql")
            .value(containsString("mask_phone(r.phone, 3, 4) AS phone")));
  }

  @Test
  void rejectsInstanceTogetherWithInlineYaml() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "metadataYaml", "metadata: {tables: []}",
                "sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("exactly one of metadataYaml or instance")));
  }

  @Test
  void rejectsMissingBothConfigSources() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("exactly one of metadataYaml or instance")));
  }

  @Test
  void rejectsPolicyYamlInInstanceMode() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "policyYaml", "policies: []",
                "sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("policyYaml cannot be combined with instance")));
  }

  @Test
  void instanceSuccessEmitsExactlyOneAuditEvent() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "sql", "SELECT id, phone FROM customer"))))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder, times(1)).record(captor.capture());
    assertEquals(AuditEvent.SUCCESS, captor.getValue().outcome());
  }

  @Test
  void instanceValidationFailureEmitsExactlyOneAuditEvent() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "metadataYaml", "metadata: {tables: []}",
                "sql", "SELECT 1"))))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(recorder, times(1)).record(captor.capture());
    assertEquals(AuditEvent.FAILURE, captor.getValue().outcome());
  }
}
