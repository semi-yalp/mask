package io.sqlmask.metaserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class AdminAuditMetaTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }
  }

  @Test
  void createInstanceEmitsAdminChange() throws Exception {
    mvc.perform(post("/api/meta/instances").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("{\"name\": \"pg1\", \"dialect\": \"postgresql\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getAllValues().stream()
        .filter(x -> "CREATE".equals(x.action()))
        .findFirst().orElseThrow();
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals("mask-metadata", e.service());
    assertEquals("pg1", e.resourceName());
    assertEquals("postgresql", e.detail().get("dialect"));
  }

  @Test
  void failedCollectEmitsFailureEvent() throws Exception {
    mvc.perform(post("/api/meta/instances/missing/collect").header("X-Api-Key", "test-key"))
        .andExpect(status().isNotFound()); // unknown instance -> 404 METADATA_INSTANCE_NOT_FOUND
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals("COLLECT", e.action());
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("METADATA_INSTANCE_NOT_FOUND", e.errorCode());
  }

  @Test
  void registerStructureEmitsAdminChangeWithTableCount() throws Exception {
    mvc.perform(post("/api/meta/instances").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("{\"name\": \"sr1\", \"dialect\": \"postgresql\"}"))
        .andExpect(status().isOk());
    mvc.perform(put("/api/meta/instances/sr1/structure").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("""
                [{"catalog":"crm","schema":"public","name":"customer_copy",
                  "columns":[{"name":"phone","type":"varchar"}]}]
                """))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getAllValues().stream()
        .filter(x -> "REGISTER_STRUCTURE".equals(x.action()))
        .findFirst().orElseThrow();
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals("mask-metadata", e.service());
    assertEquals("INSTANCE", e.resourceType());
    assertEquals("sr1", e.resourceName());
    assertEquals(Integer.valueOf(1), e.detail().get("tableCount"));
  }

  @Test
  void registerStructureWithoutTablesAuditsZeroCount() throws Exception {
    mvc.perform(post("/api/meta/instances").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("{\"name\": \"sr2\", \"dialect\": \"postgresql\"}"))
        .andExpect(status().isOk());
    mvc.perform(put("/api/meta/instances/sr2/structure").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("[]"))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getAllValues().stream()
        .filter(x -> "REGISTER_STRUCTURE".equals(x.action()))
        .findFirst().orElseThrow();
    assertEquals(Integer.valueOf(0), e.detail().get("tableCount"));
  }

  /** X-Originating-User(继承注册器等自动触发者)进入审计 detail;缺省时不写该键。 */
  @Test
  void registerStructureAuditRecordsOriginatingUserFromHeader() throws Exception {
    mvc.perform(post("/api/meta/instances").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("{\"name\": \"sr-origin\", \"dialect\": \"postgresql\"}"))
        .andExpect(status().isOk());
    mvc.perform(put("/api/meta/instances/sr-origin/structure").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .header("X-Originating-User", "registrar")
            .content("""
                [{"catalog":"crm","schema":"public","name":"customer_copy",
                  "columns":[{"name":"phone","type":"varchar"}]}]
                """))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getAllValues().stream()
        .filter(x -> "REGISTER_STRUCTURE".equals(x.action())
            && "sr-origin".equals(x.instance()))
        .findFirst().orElseThrow();
    assertEquals("registrar", e.detail().get("originatingUser"));
    mvc.perform(put("/api/meta/instances/sr-origin/structure").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("[]"))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent withoutHeader = captor.getAllValues().stream()
        .filter(x -> "REGISTER_STRUCTURE".equals(x.action())
            && "sr-origin".equals(x.instance()))
        .reduce((a, b) -> b).orElseThrow();
    org.junit.jupiter.api.Assertions.assertFalse(
        withoutHeader.detail().containsKey("originatingUser"),
        () -> String.valueOf(withoutHeader.detail()));
  }
}
