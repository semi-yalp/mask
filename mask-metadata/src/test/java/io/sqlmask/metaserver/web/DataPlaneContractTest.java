package io.sqlmask.metaserver.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The served JSON must deserialize into the core configuration model and feed
 * the existing rewrite pipeline (schema construction) unchanged.
 */
@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class DataPlaneContractTest {

  private static final String KEY = "test-key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper json;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      InMemoryMetaStore store = new InMemoryMetaStore();
      store.createInstance(new InstanceRow("pg_prod", "postgresql", null, 3));
      store.replaceStructure("pg_prod", List.of(new TableStructure("crm", "public", "customer",
          List.of(new TableStructure.ColumnStructure("id", "bigint"),
              new TableStructure.ColumnStructure("phone", "varchar(20)")))));
      store.instances.put("pg_prod", store.instances.get("pg_prod").withVersion(3));
      return store;
    }
  }

  @Test
  void servedPayloadFeedsCorePipeline() throws Exception {
    String body = mockMvc.perform(get("/api/metadata/instances/pg_prod")
            .header("X-Api-Key", KEY))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    MetadataDtos.MetadataResponse payload = json.readValue(body,
        MetadataDtos.MetadataResponse.class);
    assertEquals("postgresql", payload.dialect());

    List<TableMetadata> tables = new ArrayList<>();
    for (MetadataDtos.TablePayload table : payload.tables()) {
      List<TableMetadata.Column> columns = new ArrayList<>();
      for (MetadataDtos.ColumnPayload column : table.columns()) {
        columns.add(DialectProfiles.byName(payload.dialect()).typeResolver()
            .parseColumn(column.name(), column.type()));
      }
      tables.add(new TableMetadata(table.catalog(), table.schema(), table.name(), columns));
    }
    LoadedConfig loaded = new LoadedConfig(
        new MaskingConfig(tables, List.of(), Map.of()));
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    assertNotNull(schema.getSubSchema("crm"));
  }
}
