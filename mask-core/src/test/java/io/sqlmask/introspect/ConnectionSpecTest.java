package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionSpecTest {

  private ConnectionSpec minimal() {
    return new ConnectionSpec("postgresql", "127.0.0.1", 5432, "crm", "postgres", "pw",
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
    ConnectionSpec spec = new ConnectionSpec("postgresql", "pg.example.com", 6543, "my_db", "u", "p",
        List.of("public"), true, true, "require", 3);
    assertEquals("jdbc:postgresql://pg.example.com:6543/my_db"
        + "?sslmode=require&connectTimeout=3&socketTimeout=60&readOnly=true",
        spec.toJdbcUrl());
  }

  @Test
  void rejectsBlankDatabaseAndUser() {
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("postgresql", "h", 5432, " ", "u", "p", List.of(), false, false, "disable", 10));
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("postgresql", "h", 5432, "db", "", "p", List.of(), false, false, "disable", 10));
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("postgresql", "h", 0, "db", "u", "p", List.of(), false, false, "disable", 10));
  }

  @Test
  void schemasListIsDefensivelyCopied() {
    ConnectionSpec spec = minimal();
    spec.schemas().clear();
    assertEquals(List.of(), spec.schemas());
  }

  @Test
  void engineIsNormalizedAndValidated() {
    assertEquals("mysql", new ConnectionSpec("MySQL", "h", 3306, "shop", "u", "p",
        List.of(), false, false, "disable", 10).engine());
    assertThrows(IllegalArgumentException.class, () -> new ConnectionSpec("oracle",
        "h", 1521, "d", "u", "p", List.of(), false, false, "disable", 10));
  }

  @Test
  void mysqlUrl() {
    assertEquals("jdbc:mysql://127.0.0.1:3306/shop?connectTimeout=10&socketTimeout=60&sslMode=DISABLED&allowPublicKeyRetrieval=true",
        new ConnectionSpec("mysql", "127.0.0.1", 3306, "shop", "u", "p",
            List.of(), false, false, "disable", 10).toJdbcUrl());
    assertEquals("jdbc:mysql://h:3306/shop?connectTimeout=10&socketTimeout=60&sslMode=REQUIRED&verifyServerCertificate=false",
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p",
            List.of(), false, false, "require", 10).toJdbcUrl());
  }

  @Test
  void trinoUrl() {
    assertEquals("jdbc:trino://127.0.0.1:8080/crm?SSL=false",
        new ConnectionSpec("trino", "127.0.0.1", 8080, "crm", "u", "p",
            List.of(), false, false, "disable", 10).toJdbcUrl());
    assertEquals("jdbc:trino://h:8080/crm",
        new ConnectionSpec("trino", "h", 8080, "crm", "u", "p",
            List.of(), false, false, "require", 10).toJdbcUrl());
  }

  @Test
  void postgresqlUrlUnchanged() {
    assertEquals("jdbc:postgresql://127.0.0.1:5432/crm"
        + "?sslmode=disable&connectTimeout=10&socketTimeout=60&readOnly=true", minimal().toJdbcUrl());
  }
}
