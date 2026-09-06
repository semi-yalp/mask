package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionSpecTest {

  private ConnectionSpec minimal() {
    return new ConnectionSpec("127.0.0.1", 5432, "crm", "postgres", "pw",
        List.of(), false, false, "disable", 10);
  }

  @Test
  void buildsJdbcUrlWithSslmodeAndTimeouts() {
    assertEquals("jdbc:postgresql://127.0.0.1:5432/crm"
        + "?sslmode=disable&connectTimeout=10&socketTimeout=60&readOnly=true",
        minimal().toJdbcUrl());
  }

  @Test
  void urlEscapesNothingButKeepsGivenDatabase() {
    ConnectionSpec spec = new ConnectionSpec("pg.example.com", 6543, "my_db", "u", "p",
        List.of("public"), true, true, "require", 3);
    assertEquals("jdbc:postgresql://pg.example.com:6543/my_db"
        + "?sslmode=require&connectTimeout=3&socketTimeout=60&readOnly=true",
        spec.toJdbcUrl());
  }

  @Test
  void rejectsBlankDatabaseAndUser() {
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("h", 5432, " ", "u", "p", List.of(), false, false, "disable", 10));
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("h", 5432, "db", "", "p", List.of(), false, false, "disable", 10));
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("h", 0, "db", "u", "p", List.of(), false, false, "disable", 10));
  }

  @Test
  void schemasListIsDefensivelyCopied() {
    ConnectionSpec spec = minimal();
    spec.schemas().clear();
    assertEquals(List.of(), spec.schemas());
  }
}
