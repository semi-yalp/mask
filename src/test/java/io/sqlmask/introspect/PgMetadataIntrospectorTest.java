package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgMetadataIntrospectorTest {

  /** 第 1 条语句返回库名；第 2 条返回表数据（drop 列已由 SQL 过滤）。 */
  private Connection fakeConnection(ResultSet catalogRow, ResultSet tableRows)
      throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    PreparedStatement c2 = mock(PreparedStatement.class);
    when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(c1, c2);
    when(c1.executeQuery()).thenReturn(catalogRow);
    when(c2.executeQuery()).thenReturn(tableRows);
    return conn;
  }

  private ResultSet singleStringRow(String value) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn(value);
    return rs;
  }

  @Test
  void collectsTablesColumnsAndCatalog() throws SQLException {
    // 每行: schema, table, relkind, column, pg_type, attnum
    ResultSet tables = mock(ResultSet.class);
    when(tables.next()).thenReturn(true, true, true, false);
    when(tables.getString("schema_name")).thenReturn("public", "public", "sales");
    when(tables.getString("table_name")).thenReturn("customer", "customer", "facts");
    when(tables.getString("relkind")).thenReturn("r", "r", "p");
    when(tables.getString("column_name")).thenReturn("id", "phone", "id");
    when(tables.getString("pg_type")).thenReturn("bigint", "character varying(20)", "jsonb");
    when(tables.getInt("attnum")).thenReturn(1, 2, 1);

    Connection conn = fakeConnection(singleStringRow("crm"), tables);
    PgMetadataIntrospector introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };

    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("h", 5432, "crm", "u", "p", List.of(), false, false, "disable", 10));

    assertEquals("crm", result.catalog());
    assertEquals(2, result.tables().size());
    IntrospectionResult.TableInfo customer = result.tables().get(0);
    assertEquals("public", customer.schema());
    assertEquals("customer", customer.name());
    assertEquals(2, customer.columns().size());
    assertEquals("bigint", customer.columns().get(0).yamlType());
    assertEquals("varchar(20)", customer.columns().get(1).yamlType());
    assertTrue(result.warnings().stream()
        .anyMatch(w -> w.contains("jsonb") && w.contains("crm.sales.facts.id")));
  }

  @Test
  void emptyDatabaseYieldsWarningNotError() throws SQLException {
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    Connection conn = fakeConnection(singleStringRow("crm"), empty);
    PgMetadataIntrospector introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("h", 5432, "crm", "u", "p", List.of(), false, false, "disable", 10));
    assertTrue(result.tables().isEmpty());
    assertTrue(result.warnings().stream()
        .anyMatch(w -> w.contains("未找到任何表")));
  }

  @Test
  void connectionFailureBecomesIntrospectErrorWithoutUrl() {
    PgMetadataIntrospector introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) throws SQLException {
        throw new SQLException("FATAL: password authentication failed for user \"postgres\"");
      }
    };
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> introspector.introspect(
        new ConnectionSpec("h", 5432, "crm", "u", "p", List.of(), false, false, "disable", 10)));
    assertEquals(SqlMaskException.Code.INTROSPECT_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("password authentication failed"));
  }
}
