package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.MetadataYamlImporter;
import io.sqlmask.metaserver.service.StructureService;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class MetadataAdminControllerTest {

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
  void resetStore() {
    store.instances.clear();
    store.structures.clear();
    store.versionBumps.clear();
  }

  @Test
  void requiresApiKey() throws Exception {
    mockMvc.perform(get("/api/instances")).andExpect(status().isUnauthorized());
  }

  @Test
  void createListAndImportFlow() throws Exception {
    mockMvc.perform(post("/api/instances")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_prod","dialect":"postgresql",
                 "connection":{"host":"127.0.0.1","port":5432,"database":"db",
                               "dbUser":"user","passwordRef":"REF",
                               "sslmode":"disable","connectTimeoutSeconds":10,
                               "schemas":[],"includeViews":false}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("pg_prod"))
        .andExpect(jsonPath("$.metadataVersion").value(1));

    mockMvc.perform(get("/api/instances").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].dialect").value("postgresql"));

    MvcResult importResult = mockMvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_imported","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      columns:\\n        - { name: id, type: bigint }\\n"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableCount").value(1))
        .andExpect(jsonPath("$.metadataVersion").value(2))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertNotEquals("", importResult.getResponse()
        .getContentAsString());
  }

  @Test
  void importRejectsRowFilterWith400() throws Exception {
    mockMvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_bad","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      rowFilter: \\"status = 'active'\\"\\n      columns:\\n        - { name: id, type: bigint }\\n"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("row_filter policy on the policy service")));
  }

  @Test
  void importRejectsUnresolvableType() throws Exception {
    mockMvc.perform(post("/api/instances/import")
            .header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"pg_bad_type","dialect":"postgresql","metadataYaml":
                "metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n      name: customer\\n      columns:\\n        - { name: id, type: definitely-not-a-type }\\n"}
                """))
        .andExpect(status().isBadRequest());
  }
}
