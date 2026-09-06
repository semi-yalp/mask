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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MysqlMetadataIntrospectorTest {

  @Test
  void collectsTablesColumnsAndCatalog() throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    PreparedStatement c2 = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(c1, c2);

    ResultSet db = mock(ResultSet.class);
    when(db.next()).thenReturn(true, false);
    when(db.getString(1)).thenReturn("shop");

    ResultSet rows = mock(ResultSet.class);
    // 每行: TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, COLUMN_NAME, COLUMN_TYPE
    when(rows.next()).thenReturn(true, true, false);
    when(rows.getString("TABLE_SCHEMA")).thenReturn("shop", "shop");
    when(rows.getString("TABLE_NAME")).thenReturn("customer", "customer");
    when(rows.getString("TABLE_TYPE")).thenReturn("BASE TABLE", "BASE TABLE");
    when(rows.getString("COLUMN_NAME")).thenReturn("id", "name");
    when(rows.getString("COLUMN_TYPE")).thenReturn("bigint unsigned", "varchar(50)");

    when(c1.executeQuery()).thenReturn(db);
    when(c2.executeQuery()).thenReturn(rows);

    MysqlMetadataIntrospector introspector = new MysqlMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p", List.of(), false, false, "disable", 10));

    assertEquals("shop", result.catalog());
    assertEquals(1, result.tables().size());
    assertEquals("shop", result.tables().get(0).schema());
    assertEquals(2, result.tables().get(0).columns().size());
    assertEquals("bigint", result.tables().get(0).columns().get(0).yamlType());
    assertTrue(result.tables().get(0).columns().get(0).degraded());
    assertEquals("varchar(50)", result.tables().get(0).columns().get(1).yamlType());
    assertTrue(result.warnings().stream().anyMatch(w ->
        w.contains("shop.shop.customer.id") && w.contains("mysql type bigint unsigned")
        && w.endsWith("degraded to varchar")));
  }

  @Test
  void emptyDatabaseYieldsWarning() throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    PreparedStatement c2 = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(c1, c2);
    ResultSet db = mock(ResultSet.class);
    when(db.next()).thenReturn(true, false);
    when(db.getString(1)).thenReturn("shop");
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    when(c1.executeQuery()).thenReturn(db);
    when(c2.executeQuery()).thenReturn(empty);

    MysqlMetadataIntrospector introspector = new MysqlMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p", List.of(), false, false, "disable", 10));
    assertTrue(result.tables().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未找到任何表")));
  }

  @Test
  void connectionFailureBecomesIntrospectError() {
    MysqlMetadataIntrospector introspector = new MysqlMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) throws SQLException {
        throw new SQLException("Access denied for user 'u'@'h' (using password: YES)");
      }
    };
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> introspector.introspect(
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p", List.of(), false, false, "disable", 10)));
    assertEquals(SqlMaskException.Code.INTROSPECT_ERROR, e.getCode());
  }
}
