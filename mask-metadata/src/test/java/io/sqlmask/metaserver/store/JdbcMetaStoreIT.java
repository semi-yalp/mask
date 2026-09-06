package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.model.TableStructure.ColumnStructure;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs only when METADATA_PG_URL is set; schema is applied idempotently. */
class JdbcMetaStoreIT {

  private static JdbcTemplate jdbc;
  private static JdbcMetaStore store;

  @BeforeAll
  static void setUp() throws Exception {
    String url = System.getenv("METADATA_PG_URL");
    Assumptions.assumeTrue(url != null, "METADATA_PG_URL not set; skipping JDBC store IT");
    SingleConnectionDataSource ds = new SingleConnectionDataSource(url,
        System.getenv().getOrDefault("METADATA_PG_USER", "postgres"),
        System.getenv().getOrDefault("METADATA_PG_PASSWORD", "postgres"), true);
    jdbc = new JdbcTemplate(ds);
    try (Connection connection = ds.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("/metadata-schema.sql", JdbcMetaStoreIT.class));
    }
    store = new JdbcMetaStore(jdbc, new DataSourceTransactionManager(ds));
  }

  private static InstanceRow row(String name) {
    return new InstanceRow(name, "postgresql",
        new ConnectionInfo("127.0.0.1", 5432, "db", "user", "SQLMASK_TEST_PASSWORD",
            "disable", 10, List.of("public"), false), 1);
  }

  @Test
  void crudLifecycleBumpsVersion() {
    String name = "it_" + UUID.randomUUID();
    store.createInstance(row(name));
    assertEquals(1, store.findInstance(name).orElseThrow().metadataVersion());

    store.updateInstance(name, row(name).connection());
    assertEquals(2, store.findInstance(name).orElseThrow().metadataVersion());

    TableStructure table = new TableStructure("crm", "public", "customer", List.of(
        new ColumnStructure("id", "bigint"), new ColumnStructure("phone", "varchar")));
    store.replaceStructure(name, List.of(table));
    assertEquals(3, store.findInstance(name).orElseThrow().metadataVersion());

    List<TableStructure> loaded = store.loadStructure(name);
    assertEquals(1, loaded.size());
    assertEquals(table, loaded.get(0));

    store.deleteInstance(name);
    assertTrue(store.findInstance(name).isEmpty());
  }
}
