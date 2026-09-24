package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectRegistryTest {

  @Test
  void createsPostgresqlAdapterByNameCaseInsensitively() {
    assertEquals("postgresql", DialectRegistry.create("PostgreSQL").name());
  }

  @Test
  void unknownDialectFailsWithSupportedList() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> DialectRegistry.create("oracle"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("postgresql"), () -> e.getMessage());
  }
}
