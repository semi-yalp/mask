package io.sqlmask.introspect.udf;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Routing of {@link UdfIntrospectors#byEngine(String)} and its error contract. */
class UdfIntrospectorsTest {

  @Test
  void routesEnginesCaseInsensitively() {
    assertInstanceOf(PgUdfIntrospector.class, UdfIntrospectors.byEngine("postgresql"));
    assertInstanceOf(PgUdfIntrospector.class, UdfIntrospectors.byEngine(" PostgreSQL "));
    assertInstanceOf(MysqlUdfIntrospector.class, UdfIntrospectors.byEngine("mysql"));
    assertInstanceOf(MysqlUdfIntrospector.class, UdfIntrospectors.byEngine("MySQL"));
  }

  @Test
  void unsupportedEngineIsAConfigurationError() {
    for (String engine : new String[] {"trino", "oracle", "", null}) {
      SqlMaskException e = assertThrows(SqlMaskException.class,
          () -> UdfIntrospectors.byEngine(engine));
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
      assertTrueMentionsEngine(e);
    }
  }

  private static void assertTrueMentionsEngine(SqlMaskException e) {
    assertTrue(e.getMessage().contains("unsupported engine"), e.getMessage());
  }
}
