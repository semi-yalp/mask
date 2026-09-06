package io.sqlmask.metaserver.web;

import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.metaserver.service.CredentialResolver;
import io.sqlmask.metaserver.service.IntrospectorFactory;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class CollectControllerTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private InMemoryMetaStore store;
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
  void seed() {
    when(credentialResolver.resolve(any())).thenReturn("pw");
    store.instances.clear();
    store.structures.clear();
    store.createInstance(new io.sqlmask.metaserver.model.InstanceRow("pg_prod", "postgresql",
        new io.sqlmask.metaserver.model.ConnectionInfo("127.0.0.1", 5432, "db", "user",
            "SQLMASK_PG_PASSWORD", "disable", 10, List.of(), false), 1));
  }

  @Test
  void collectReturnsCountsAndWarnings() throws Exception {
    when(introspectorFactory.byEngine("postgresql")).thenReturn(spec ->
        new IntrospectionResult("db",
            List.of(new IntrospectionResult.TableInfo("db", "public", "customer",
                List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "int8", false)))),
            List.of()));
    mockMvc.perform(post("/api/instances/pg_prod/collect").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableCount").value(1))
        .andExpect(jsonPath("$.metadataVersion").value(2));
  }

  @Test
  void collectFailureMapsTo502AndKeepsStructure() throws Exception {
    store.replaceStructure("pg_prod", List.of(new io.sqlmask.metaserver.model.TableStructure(
        "db", "public", "customer",
        List.of(new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("id", "bigint")))));
    when(introspectorFactory.byEngine("postgresql")).thenReturn(spec -> {
      throw new io.sqlmask.error.SqlMaskException(
          io.sqlmask.error.SqlMaskException.Code.INTROSPECT_ERROR, "db down");
    });
    mockMvc.perform(post("/api/instances/pg_prod/collect").header("X-Api-Key", KEY))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("INTROSPECT_ERROR"));
    org.junit.jupiter.api.Assertions.assertEquals(1, store.loadStructure("pg_prod").size());
  }
}
