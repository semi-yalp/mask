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
  private static javax.sql.DataSource ds;
  private static JdbcTemplate jdbc;
  private static JdbcMetaStore store;

  @BeforeAll
  static void setUp() throws Exception {
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

  @Test
  void kindRoundTripsThroughStore() {
    String name = "it_" + UUID.randomUUID();
    store.createInstance(row(name));
    TableStructure plain = new TableStructure("crm", "public", "customer",
        List.of(new ColumnStructure("id", "bigint")));
    TableStructure view = new TableStructure("crm", "public", "customer_v", "view",
        List.of(new ColumnStructure("id", "bigint")));
    store.replaceStructure(name, List.of(plain, view));
    List<TableStructure> loaded = store.loadStructure(name);
    assertEquals("table", loaded.get(0).kind());
    assertEquals("view", loaded.get(1).kind());
    store.deleteInstance(name);
  }

  @Test
  void schemaMigrationRestoresKindColumnWithDefault() throws Exception {
    // 模拟存量库：删掉 kind 列后重跑全量 DDL，ALTER ... ADD COLUMN IF NOT EXISTS 负责补列
    jdbc.execute("ALTER TABLE meta_table DROP COLUMN kind");
    try (Connection connection = ds.getConnection()) {
      ScriptUtils.executeSqlScript(connection,
          new ClassPathResource("/metadata-schema.sql", JdbcMetaStoreTest.class));
    }
    Integer kindColumns = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_name = 'meta_table' AND column_name = 'kind'", Integer.class);
    assertEquals(1, kindColumns);

    // 存量写入路径（INSERT 不带 kind 列）依旧可用，默认值补齐
    String name = "it_" + UUID.randomUUID();
    store.createInstance(row(name));
    jdbc.update("INSERT INTO meta_table (instance_id, catalog, schema_name, table_name, position) "
        + "SELECT id, 'crm', 'public', 'legacy_t', 0 FROM meta_instance WHERE name = ?", name);
    // 按 instance 收敛查询：共享外部库（METADATA_PG_URL）多次运行时同名表可能多行
    assertEquals("table", jdbc.queryForObject(
        "SELECT t.kind FROM meta_table t JOIN meta_instance i ON i.id = t.instance_id "
            + "WHERE t.table_name = 'legacy_t' AND i.name = ?", String.class, name));
  }
}
