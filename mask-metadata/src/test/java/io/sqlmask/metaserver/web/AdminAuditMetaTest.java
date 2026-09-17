package io.sqlmask.metaserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.metaserver.service.CredentialResolver;
import io.sqlmask.metaserver.service.IntrospectorFactory;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin-surface audit contract of the metadata service: every mutation
 * endpoint emits one ADMIN_CHANGE event (SUCCESS or FAILURE), service name
 * mask-metadata, authKind marked by the API-key filter. Data plane
 * (MetadataDataController) stays silent.
 */
@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class AdminAuditMetaTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mvc;
  @Autowired
  private InMemoryMetaStore store;
  @MockBean
  private AuditRecorder recorder;
  @MockBean
  private IntrospectorFactory introspectorFactory;
  @MockBean
  private CredentialResolver credentialResolver;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }
  }

  @BeforeEach
  void resetStore() {
    when(credentialResolver.resolve(any())).thenReturn("pw");
    store.instances.clear();
    store.structures.clear();
    store.versionBumps.clear();
  }

  private List<AuditEvent> recorded() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    return captor.getAllValues();
  }

  @Test
  void createInstanceEmitsAdminChange() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", KEY)
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
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals(io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY, e.authKind());
  }

  @Test
  void updateDeleteAndImportEmitEvents() throws Exception {
    mvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_imported","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      columns:\\n        - { name: id, type: bigint }\\n"}
                """))
        .andExpect(status().isOk());
    mvc.perform(put("/api/instances/pg_imported").header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/pg_imported").header("X-Api-Key", KEY))
        .andExpect(status().isOk());
    List<AuditEvent> events = recorded();
    AuditEvent importEvent = events.stream()
        .filter(x -> "IMPORT".equals(x.action()) && "TABLES".equals(x.resourceType()))
        .findFirst().orElseThrow();
    assertEquals("pg_imported", importEvent.resourceName());
    assertEquals(1, importEvent.detail().get("tableCount"));
    assertEquals(1, importEvent.detail().get("columnCount"));
    AuditEvent update = events.stream()
        .filter(x -> "UPDATE".equals(x.action()) && "INSTANCE".equals(x.resourceType()))
        .findFirst().orElseThrow();
    assertEquals("pg_imported", update.resourceName());
    assertEquals(AuditEvent.SUCCESS, update.outcome());
    AuditEvent delete = events.stream()
        .filter(x -> "DELETE".equals(x.action()) && "INSTANCE".equals(x.resourceType()))
        .findFirst().orElseThrow();
    assertEquals("pg_imported", delete.resourceName());
    assertEquals(AuditEvent.SUCCESS, delete.outcome());
  }

  @Test
  void successCollectEmitsEventWithEngineAndDatabase() throws Exception {
    store.createInstance(new io.sqlmask.metaserver.model.InstanceRow("pg_prod", "postgresql",
        new io.sqlmask.metaserver.model.ConnectionInfo("127.0.0.1", 5432, "shopdb", "user",
            "SQLMASK_PG_PASSWORD", "disable", 10, List.of(), false), 1));
    when(introspectorFactory.byEngine("postgresql")).thenReturn(spec ->
        new IntrospectionResult("shopdb",
            List.of(new IntrospectionResult.TableInfo("shopdb", "public", "customer",
                List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "int8", false)))),
            List.of()));
    mvc.perform(post("/api/instances/pg_prod/collect").header("X-Api-Key", KEY))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals("COLLECT", e.action());
    assertEquals("INSTANCE", e.resourceType());
    assertEquals("pg_prod", e.resourceName());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("postgresql", e.detail().get("engine"));
    assertEquals("shopdb", e.detail().get("database"));
  }

  @Test
  void failedCollectEmitsFailureEvent() throws Exception {
    // 无连接配置的实例：collect 在 service 内抛 CONFIG_ERROR → 400 + FAILURE 事件
    // （missing 实例是 404/METADATA_INSTANCE_NOT_FOUND，不符合本用例断言的 400/CONFIG_ERROR）
    store.createInstance(new io.sqlmask.metaserver.model.InstanceRow("pg_nolink", "postgresql",
        null, 1));
    mvc.perform(post("/api/instances/pg_nolink/collect").header("X-Api-Key", KEY))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals("COLLECT", e.action());
    assertEquals("INSTANCE", e.resourceType());
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("CONFIG_ERROR", e.errorCode());
  }
}
