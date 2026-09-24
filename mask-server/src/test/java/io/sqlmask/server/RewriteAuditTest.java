package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RewriteAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: mask_phone
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Test
  void successfulRewriteEmitsOneEventWithJoinedSql() throws Exception {
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"metadataYaml": %s, "sql": "SELECT phone FROM customer;",
                 "dialect": "postgresql", "user": "alice", "groups": ["devs"]}
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(YAML))))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.REWRITE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("sql-mask", e.service());
    assertEquals("postgresql", e.dialect());
    assertEquals(1, e.statementCount());
    assertEquals(Boolean.TRUE, e.masked());
    assertEquals(Boolean.FALSE, e.rowFiltered());
    assertEquals("SELECT phone FROM customer;", e.originalSql());
    assertEquals("alice", e.actorUser());
    assertEquals(List.of("devs"), e.actorGroups());
    assertNull(e.errorCode());
  }

  @Test
  void failedRewriteEmitsFailureEventAndStillReturns400() throws Exception {
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"metadataYaml": %s, "sql": "DELETE FROM customer;", "dialect": "postgresql"}
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(YAML))))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("UNSUPPORTED_STATEMENT", e.errorCode());
    assertEquals("sql-mask", e.service());
  }

  @Test
  void configErrorEmitsFailureEventWithConfigErrorCode() throws Exception {
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": \"\", \"sql\": \"SELECT 1\"}"))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    assertEquals("CONFIG_ERROR", captor.getValue().errorCode());
  }
}
