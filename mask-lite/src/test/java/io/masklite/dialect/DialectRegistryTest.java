package io.masklite.dialect;

import io.masklite.error.SqlMaskException;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registry lookup and profile wiring of the dialect seam. */
class DialectRegistryTest {

  @Test
  void createsRegisteredDialectByNameCaseInsensitively() {
    assertTrue(DialectRegistry.create("postgresql") instanceof PostgresDialect);
    assertTrue(DialectRegistry.create("PostgreSQL") instanceof PostgresDialect);
  }

  @Test
  void unknownDialectFailsWithSupportedList() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> DialectRegistry.create("mysql"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("unsupported dialect 'mysql'"), e.getMessage());
    assertTrue(e.getMessage().contains("postgresql"), e.getMessage());
  }

  @Test
  void nullDialectFails() {
    assertThrows(SqlMaskException.class, () -> DialectRegistry.create(null));
  }

  @Test
  void defaultDialectIsPostgresql() {
    assertEquals(PostgresDialect.NAME, DialectRegistry.DEFAULT);
    assertEquals("postgresql", DialectRegistry.DEFAULT);
  }

  @Test
  void profileWiringIsConsistent() {
    PostgresDialect dialect = new PostgresDialect();
    assertEquals(PostgresDialect.NAME, dialect.name());
    assertEquals(PostgresDialect.NAME, dialect.profile().name());
    assertSame(dialect.profile().identifierPolicy(), dialect.identifiers());
    assertTrue(dialect.profile().typeResolver() instanceof PostgresqlTypeResolver);
    assertTrue(dialect.profile().functionTable() == PostgresqlFunctions.TABLE);
  }

  /** The interface alone must suffice for parse/unparse, as the pipeline uses it. */
  @Test
  void dialectInterfaceSufficesForParseAndRender() {
    Dialect dialect = DialectRegistry.create("postgresql");
    SqlNode parsed = dialect.parse("select 1 as a", 1);
    assertEquals("SELECT 1 AS a", dialect.unparse(parsed));
  }

  @Test
  void nonQueryStillRejectedThroughInterface() {
    Dialect dialect = DialectRegistry.create("postgresql");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> dialect.parse("delete from t", 1));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }
}
