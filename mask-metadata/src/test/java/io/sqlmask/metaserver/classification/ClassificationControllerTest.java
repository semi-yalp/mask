package io.sqlmask.metaserver.classification;

import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class ClassificationControllerTest {

  private static final String KEY = "test-key";
  private static final String CUSTOMER = "crm.public.customer";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private MetadataService instances;
  @Autowired
  private StructureService structures;
  @Autowired
  private InMemoryMetaStore metaStore;
  @Autowired
  private InMemoryClassificationStore classificationStore;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }

    @Bean
    @Primary
    InMemoryClassificationStore inMemoryClassificationStore() {
      return new InMemoryClassificationStore();
    }
  }

  @BeforeEach
  void seed() {
    metaStore.instances.clear();
    metaStore.structures.clear();
    metaStore.versionBumps.clear();
    classificationStore.clear();
    instances.create("pg_prod", "postgresql", null, null);
    structures.replace("pg_prod", List.of(
        new TableStructure("crm", "public", "customer", List.of(
            new TableStructure.ColumnStructure("id", "bigint"),
            new TableStructure.ColumnStructure("phone_number", "varchar"),
            new TableStructure.ColumnStructure("email", "varchar"),
            new TableStructure.ColumnStructure("created_at", "timestamptz")))));
  }

  @Test
  void autoClassifyEndpointReturnsCountsThenIsIdempotent() throws Exception {
    mockMvc.perform(post("/api/classification/instances/pg_prod/auto").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.columnsConsidered").value(4))
        .andExpect(jsonPath("$.created").value(2))
        .andExpect(jsonPath("$.alreadyClassified").value(0));

    mockMvc.perform(post("/api/classification/instances/pg_prod/auto").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(0))
        .andExpect(jsonPath("$.alreadyClassified").value(2));
  }

  @Test
  void listReturnsAutoRowsWithLevelAndSource() throws Exception {
    mockMvc.perform(post("/api/classification/instances/pg_prod/auto").header("X-Api-Key", KEY))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/classification/instances/pg_prod").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].columnKey").value(CUSTOMER + ".email"))
        .andExpect(jsonPath("$[0].category").value("CONTACT"))
        .andExpect(jsonPath("$[0].level").value("MEDIUM"))
        .andExpect(jsonPath("$[0].source").value("AUTO"))
        .andExpect(jsonPath("$[1].columnKey").value(CUSTOMER + ".phone_number"))
        .andExpect(jsonPath("$[1].category").value("CONTACT"))
        .andExpect(jsonPath("$[1].level").value("HIGH"))
        .andExpect(jsonPath("$[1].updatedAt").isNotEmpty());
  }

  @Test
  void putUpsertsManualClassification() throws Exception {
    mockMvc.perform(put("/api/classification/instances/pg_prod").header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"columnKey":"crm.public.customer.created_at","category":"pii","level":"LOW",
                 "note":"audit timestamp"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.columnKey").value(CUSTOMER + ".created_at"))
        .andExpect(jsonPath("$.category").value("PII"))
        .andExpect(jsonPath("$.level").value("LOW"))
        .andExpect(jsonPath("$.source").value("MANUAL"))
        .andExpect(jsonPath("$.note").value("audit timestamp"))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
  }

  @Test
  void putRejectsUnknownCategoryLevelAndMissingFields() throws Exception {
    mockMvc.perform(put("/api/classification/instances/pg_prod").header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"columnKey":"crm.public.customer.id","category":"SECRET","level":"HIGH"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message")
            .value(org.hamcrest.Matchers.containsString("IDENTITY, PII, CONTACT")));

    mockMvc.perform(put("/api/classification/instances/pg_prod").header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"columnKey":"crm.public.customer.id","category":"PII","level":"EXTREME"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("HIGH, MEDIUM, LOW")));

    mockMvc.perform(put("/api/classification/instances/pg_prod").header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"category\":\"PII\",\"level\":\"HIGH\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message")
            .value(org.hamcrest.Matchers.containsString("columnKey, category and level are required")));
  }

  @Test
  void deleteIsIdempotentAndReportsOutcome() throws Exception {
    mockMvc.perform(post("/api/classification/instances/pg_prod/auto").header("X-Api-Key", KEY))
        .andExpect(status().isOk());

    mockMvc.perform(delete("/api/classification/instances/pg_prod")
            .header("X-Api-Key", KEY).param("column", CUSTOMER + ".phone_number"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(true))
        .andExpect(jsonPath("$.columnKey").value(CUSTOMER + ".phone_number"));

    mockMvc.perform(delete("/api/classification/instances/pg_prod")
            .header("X-Api-Key", KEY).param("column", CUSTOMER + ".phone_number"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(false));
  }

  @Test
  void overviewAggregatesTotalsAndPerInstance() throws Exception {
    mockMvc.perform(post("/api/classification/instances/pg_prod/auto").header("X-Api-Key", KEY))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/classification/overview").header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalClassified").value(2))
        .andExpect(jsonPath("$.high").value(1))
        .andExpect(jsonPath("$.medium").value(1))
        .andExpect(jsonPath("$.low").value(0))
        .andExpect(jsonPath("$.instances.length()").value(1))
        .andExpect(jsonPath("$.instances[0].instance").value("pg_prod"))
        .andExpect(jsonPath("$.instances[0].columns").value(4))
        .andExpect(jsonPath("$.instances[0].classified").value(2))
        .andExpect(jsonPath("$.instances[0].high").value(1))
        .andExpect(jsonPath("$.instances[0].medium").value(1))
        .andExpect(jsonPath("$.instances[0].low").value(0));
  }

  @Test
  void unknownInstanceIs404OnListPutDeleteAndAuto() throws Exception {
    mockMvc.perform(get("/api/classification/instances/ghost").header("X-Api-Key", KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("METADATA_INSTANCE_NOT_FOUND"));
    mockMvc.perform(post("/api/classification/instances/ghost/auto").header("X-Api-Key", KEY))
        .andExpect(status().isNotFound());
    mockMvc.perform(put("/api/classification/instances/ghost").header("X-Api-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"columnKey\":\"a.b.c.d\",\"category\":\"PII\",\"level\":\"HIGH\"}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/classification/instances/ghost")
            .header("X-Api-Key", KEY).param("column", "a.b.c.d"))
        .andExpect(status().isNotFound());
  }
}
