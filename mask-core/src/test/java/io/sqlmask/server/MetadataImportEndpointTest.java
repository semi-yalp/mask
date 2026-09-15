package io.sqlmask.server;

import io.sqlmask.metadataclient.MetadataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Import-from-metaserver endpoint with a stubbed structure fetcher. */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import(MetadataImportEndpointTest.StubFetcher.class)
class MetadataImportEndpointTest {

  @TestConfiguration
  static class StubFetcher {
    @Bean
    MetadataStructureFetcher metadataStructureFetcher() {
      return (baseUrl, apiKey, instance) -> SNAPSHOT.get();
    }
  }

  private static final MetadataClient.MetadataSnapshot DEFAULT_SNAPSHOT =
      new MetadataClient.MetadataSnapshot("meta_pg", "postgresql", 7,
          List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
              List.of(new MetadataClient.ColumnSnapshot("phone", "varchar")))));

  private static final AtomicReference<MetadataClient.MetadataSnapshot> SNAPSHOT =
      new AtomicReference<>(DEFAULT_SNAPSHOT);

  @Autowired
  private MockMvc mvc;

  @Autowired
  private io.sqlmask.policyserver.PolicyService service;

  @BeforeEach
  void cleanUp() {
    SNAPSHOT.set(DEFAULT_SNAPSHOT);
    try {
      service.deleteInstance("imported");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteInstance("pg_prod");
    } catch (io.sqlmask.error.SqlMaskException e) {
      // 有策略等遗留时忽略（其它测试的实例不强制清）
    }
  }

  @Test
  void createsInstanceFromSnapshot() throws Exception {
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://localhost:8082\","
                + " \"metadataApiKey\": \"k\", \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("imported"))
        .andExpect(jsonPath("$.dialect").value("postgresql"))
        .andExpect(jsonPath("$.tables[0].name").value("customer"))
        .andExpect(jsonPath("$.tables[0].columns[0].type").value("varchar"));
  }

  @Test
  void updatesTablesOfExistingInstance() throws Exception {
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk());
    SNAPSHOT.set(new MetadataClient.MetadataSnapshot("meta_pg", "postgresql", 8,
        List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
            List.of(new MetadataClient.ColumnSnapshot("phone", "varchar"),
                new MetadataClient.ColumnSnapshot("email", "varchar"))))));
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables[0].columns.length()").value(2));
  }

  @Test
  void dialectMismatchIsRejected() throws Exception {
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isOk());
    SNAPSHOT.set(new MetadataClient.MetadataSnapshot("meta_pg", "mysql", 9,
        List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
            List.of(new MetadataClient.ColumnSnapshot("phone", "varchar"))))));
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\","
                + " \"metadataInstance\": \"meta_pg\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void missingMetadataFieldsAreRejected() throws Exception {
    // 守卫：缺 metadataInstance 时以 400 CONFIG_ERROR 拒绝（存量行为回归覆盖）
    mvc.perform(post("/api/instances/imported/import-metadata")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataBaseUrl\": \"http://x\", \"metadataApiKey\": \"k\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
}
