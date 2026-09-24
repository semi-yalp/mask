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
}
