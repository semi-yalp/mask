package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real pg_catalog introspection against an embedded PostgreSQL: schema
 * filtering, view inclusion, type mapping (including degraded types) and the
 * no-tables warning — the paths a mocked Connection cannot reach.
 */
class PgMetadataIntrospectorRealTest {

  private static EmbeddedPostgres embedded;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void startDatabase() throws IOException {
    embedded = EmbeddedPostgres.builder().start();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        embedded.getJdbcUrl("postgres", "postgres")));
    jdbc.execute("CREATE SCHEMA sales");
    jdbc.execute("CREATE TABLE sales.customer (id bigint, phone varchar(20),"
        + " score numeric(10,2), flag boolean, created timestamptz, extra jsonb)");
    jdbc.execute("CREATE VIEW sales.active AS SELECT id FROM sales.customer");
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    if (embedded != null) {
      embedded.close();
    }
  }

  private static ConnectionSpec spec(List<String> schemas, boolean includeViews) {
    return new ConnectionSpec("postgresql", "127.0.0.1", embedded.getPort(), "postgres",
        "postgres", "", schemas, includeViews, false, "disable", 10);
  }

  @Test
  void introspectsTablesColumnsAndTypesUnderSchemaFilter() {
    IntrospectionResult result = new PgMetadataIntrospector().introspect(
        spec(List.of("sales"), false));
    assertEquals("postgres", result.catalog());
    assertEquals(1, result.tables().size(), () -> result.tables().toString());
    IntrospectionResult.TableInfo table = result.tables().get(0);
    assertEquals("sales", table.schema());
    assertEquals("customer", table.name());
    List<IntrospectionResult.ColumnInfo> columns = table.columns();
    assertEquals(6, columns.size(), () -> columns.toString());
    assertEquals("id", columns.get(0).name());
    assertEquals("bigint", columns.get(0).yamlType());
    assertEquals("varchar(20)", columns.get(1).yamlType());
    assertEquals("numeric(10,2)", columns.get(2).yamlType());
    assertEquals("boolean", columns.get(3).yamlType());
    assertEquals("timestamptz", columns.get(4).yamlType());
    // jsonb is outside the supported set: kept as varchar with a degraded marker
    assertEquals("varchar", columns.get(5).yamlType());
    assertTrue(columns.get(5).degraded());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("jsonb")),
        () -> result.warnings().toString());
  }

  @Test
  void includeViewsBringsTheViewIn() {
    IntrospectionResult views = new PgMetadataIntrospector().introspect(
        spec(List.of("sales"), true));
    assertEquals(2, views.tables().size(), () -> views.tables().toString());
  }

  @Test
  void unknownSchemaWarnsAboutNoTables() {
    IntrospectionResult result = new PgMetadataIntrospector().introspect(
        spec(List.of("no_such_schema"), false));
    assertTrue(result.tables().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未找到任何表")),
        () -> result.warnings().toString());
  }

  @Test
  void unreachableServerFailsAsIntrospectError() {
    ConnectionSpec dead = new ConnectionSpec("postgresql", "127.0.0.1", 1, "postgres",
        "postgres", "", List.of("public"), false, false, "disable", 1);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new PgMetadataIntrospector().introspect(dead));
    assertEquals(SqlMaskException.Code.INTROSPECT_ERROR, e.getCode());
    assertTrue(e.getMessage().startsWith("metadata introspection failed:"),
        () -> e.getMessage());
  }
}
