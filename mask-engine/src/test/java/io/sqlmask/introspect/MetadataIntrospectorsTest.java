package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataIntrospectorsTest {

  @Test
  void resolvesPostgresqlCaseInsensitively() {
    assertInstanceOf(PgMetadataIntrospector.class,
        MetadataIntrospectors.byEngine("PostgreSQL"));
  }

  @Test
  void unknownEngineThrowsConfigError() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> MetadataIntrospectors.byEngine("oracle"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
