package io.sqlmask.metaserver.store;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.model.TableStructure.ColumnStructure;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Runs against a real PostgreSQL: an embedded instance by default, or the
 * database behind {@code METADATA_PG_URL} when set (user/password default to
 * {@code postgres}); schema is applied idempotently.
 */
class JdbcMetaStoreTest {

  private static EmbeddedPostgres embedded;
  private static JdbcTemplate jdbc;
  private static JdbcMetaStore store;

  @BeforeAll
  static void setUp() throws Exception {
    javax.sql.DataSource ds;
    String url = System.getenv("METADATA_PG_URL");
    if (url != null) {
      ds = new SingleConnectionDataSource(url,
          System.getenv().getOrDefault("METADATA_PG_USER", "postgres"),
          System.getenv().getOrDefault("METADATA_PG_PASSWORD", "postgres"), true);
    } else {
      embedded = EmbeddedPostgres.builder().start();
      ds = embedded.getPostgresDatabase();
    }
    jdbc = new JdbcTemplate(ds);
    try (Connection connection = ds.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("/metadata-schema.sql", JdbcMetaStoreTest.class));
    }
    store = new JdbcMetaStore(jdbc, new DataSourceTransactionManager(ds));
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (embedded != null) {
      embedded.close();
    }
  }

  private static InstanceRow row(String name) {
    return new InstanceRow(name, "postgresql", null,
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

  @Test
  void engineRoundTripsThroughStore() {
    ConnectionInfo conn = new ConnectionInfo("h", 9030, "db", "u", "REF", "disable", 5, List.of(), false);
    store.createInstance(new InstanceRow("sr-eng", "mysql", "starrocks", conn, 1));
    assertEquals("starrocks", store.findInstance("sr-eng").orElseThrow().engine());
  }
}
