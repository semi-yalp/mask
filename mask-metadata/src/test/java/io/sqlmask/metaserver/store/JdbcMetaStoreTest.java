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

  @Test
  void engineRoundTripsThroughStore() {
    ConnectionInfo conn = new ConnectionInfo("h", 9030, "db", "u", "REF", "disable", 5, List.of(), false);
    store.createInstance(new InstanceRow("sr-eng", "mysql", "starrocks", conn, 1));
    assertEquals("starrocks", store.findInstance("sr-eng").orElseThrow().engine());
  }

  /**
   * Gap §4.7 #1 (P0): every {@code createInstance} connection field written to
   * PostgreSQL must come back byte-identical through {@code findInstance} —
   * the {@code ?::jsonb} schemas binding and the column map are the load-bearing
   * path behind the production import->collect chain.
   */
  @Test
  void connectionFieldsRoundTripThroughStore() {
    String name = "it_" + UUID.randomUUID();
    ConnectionInfo connection = new ConnectionInfo("db.internal", 55432, "app_db", "svc_user",
        "SQLMASK_REF_PWD", "verify-full", 7, List.of("public", "crm", "raw"), true);
    store.createInstance(new InstanceRow(name, "postgresql", null, connection, 1));

    ConnectionInfo loaded = store.findInstance(name).orElseThrow().connection();
    assertEquals("db.internal", loaded.host());
    assertEquals(55432, loaded.port());
    assertEquals("app_db", loaded.database());
    assertEquals("svc_user", loaded.dbUser());
    assertEquals("SQLMASK_REF_PWD", loaded.passwordRef());
    assertEquals("verify-full", loaded.sslmode());
    assertEquals(7, loaded.connectTimeoutSeconds());
    assertEquals(List.of("public", "crm", "raw"), loaded.schemas());
    assertTrue(loaded.includeViews());
    store.deleteInstance(name);
  }

  /**
   * Gap §4.7 #1 (P0): an instance created without a connection (the YAML
   * import shape, {@code connection == null}) must round-trip back to null —
   * never to a half-initialized group with defaulted port/sslmode.
   */
  @Test
  void nullConnectionRoundTripsAsNull() {
    String name = "it_" + UUID.randomUUID();
    store.createInstance(new InstanceRow(name, "postgresql", null, null, 1));
    assertEquals(null, store.findInstance(name).orElseThrow().connection());
    store.deleteInstance(name);
  }

  /** Companion to the two above: updating a connection-bearing instance to null must clear the stored group. */
  @Test
  void updateInstanceToNullConnectionClearsPersistedFields() {
    String name = "it_" + UUID.randomUUID();
    ConnectionInfo connection = new ConnectionInfo("host-a", 6000, "legacy", "old_user",
        "REF", "disable", 3, List.of("public"), false);
    store.createInstance(new InstanceRow(name, "postgresql", null, connection, 1));
    assertEquals(connection, store.findInstance(name).orElseThrow().connection());

    store.updateInstance(name, null);
    assertEquals(null, store.findInstance(name).orElseThrow().connection());
    assertEquals(2, store.findInstance(name).orElseThrow().metadataVersion());
    store.deleteInstance(name);
  }
}
