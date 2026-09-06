package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class MetadataDataControllerTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private InMemoryMetaStore store;

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
    store.instances.clear();
    store.structures.clear();
    store.createInstance(new io.sqlmask.metaserver.model.InstanceRow("pg_prod", "postgresql",
        null, 1));
    store.replaceStructure("pg_prod", List.of(
        new io.sqlmask.metaserver.model.TableStructure("crm", "public", "customer",
            List.of(new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("id", "bigint"),
                new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("phone", "varchar"))),
        new io.sqlmask.metaserver.model.TableStructure("crm", "public", "orders",
            List.of(new io.sqlmask.metaserver.model.TableStructure.ColumnStructure("id", "bigint")))));
    store.instances.put("pg_prod", store.instances.get("pg_prod").withVersion(7));
  }

  @Test
  void servesInstanceList() throws Exception {
    mockMvc.perform(get("/api/metadata/instances").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("pg_prod"))
        .andExpect(jsonPath("$[0].dialect").value("postgresql"))
        .andExpect(jsonPath("$[0].metadataVersion").value(7));
  }

  @Test
  void servesStructureWithoutRowFilter() throws Exception {
    mockMvc.perform(get("/api/metadata/instances/pg_prod").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.metadataVersion").value(7))
        .andExpect(jsonPath("$.tables.length()").value(2))
        .andExpect(jsonPath("$.tables[0].columns[1].type").value("varchar"))
        .andExpect(jsonPath("$.rowFilter").doesNotExist())
        .andExpect(jsonPath("$.tables[0].rowFilter").doesNotExist());
  }

  @Test
  void servesVersionOnly() throws Exception {
    mockMvc.perform(get("/api/metadata/instances/pg_prod/version").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.metadataVersion").value(7));
  }

  @Test
  void unknownInstanceIs404() throws Exception {
    mockMvc.perform(get("/api/metadata/instances/ghost").header("X-Api-Key", KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("METADATA_INSTANCE_NOT_FOUND"));
  }
}
