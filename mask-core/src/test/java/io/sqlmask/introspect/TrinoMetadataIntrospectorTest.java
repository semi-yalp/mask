package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrinoMetadataIntrospectorTest {

  @Test
  void collectsTablesColumnsAndCatalog() throws SQLException {
    Connection conn = mock(Connection.class);
    // Trino 无 SELECT DATABASE() 标题查询：整个 introspect 只有一条 information_schema 查询
    PreparedStatement c1 = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(c1);

    ResultSet rows = mock(ResultSet.class);
    // 每行: TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE
    when(rows.next()).thenReturn(true, true, true, false);
    when(rows.getString("TABLE_SCHEMA")).thenReturn("shop", "shop", "shop");
    when(rows.getString("TABLE_NAME")).thenReturn("customer", "customer", "customer");
    when(rows.getString("COLUMN_NAME")).thenReturn("id", "created_at", "tags");
    when(rows.getString("DATA_TYPE"))
        .thenReturn("bigint", "timestamp(3) with time zone", "array(integer)");
    when(c1.executeQuery()).thenReturn(rows);

    TrinoMetadataIntrospector introspector = new TrinoMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("trino", "h", 8080, "shop", "u", "p", List.of(), false, false, "disable", 10));

    verify(conn, times(1)).prepareStatement(anyString());
    assertEquals("shop", result.catalog());
    assertEquals(1, result.tables().size());
    assertEquals("shop", result.tables().get(0).schema());
    assertEquals("customer", result.tables().get(0).name());
    assertEquals(3, result.tables().get(0).columns().size());
    assertEquals("bigint", result.tables().get(0).columns().get(0).yamlType());
    assertFalse(result.tables().get(0).columns().get(0).degraded());
    assertEquals("timestamp(3) with time zone",
        result.tables().get(0).columns().get(1).yamlType());
    assertFalse(result.tables().get(0).columns().get(1).degraded());
    assertEquals("varchar", result.tables().get(0).columns().get(2).yamlType());
    assertTrue(result.tables().get(0).columns().get(2).degraded());
    assertTrue(result.warnings().stream().anyMatch(w ->
        w.contains("shop.shop.customer.tags") && w.contains("trino type array(integer)")
        && w.endsWith("degraded to varchar")));
  }

  @Test
  void emptyCatalogYieldsWarning() throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(c1);
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    when(c1.executeQuery()).thenReturn(empty);

    TrinoMetadataIntrospector introspector = new TrinoMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("trino", "h", 8080, "shop", "u", "p", List.of(), false, false, "disable", 10));
    assertTrue(result.tables().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未找到任何表")));
  }

  @Test
  void connectionFailureBecomesIntrospectError() {
    TrinoMetadataIntrospector introspector = new TrinoMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) throws SQLException {
        throw new SQLException("Authentication failed: access denied for user 'u'");
      }
    };
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> introspector.introspect(
        new ConnectionSpec("trino", "h", 8080, "shop", "u", "p", List.of(), false, false, "disable", 10)));
    assertEquals(SqlMaskException.Code.INTROSPECT_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("Authentication failed"));
  }
}
