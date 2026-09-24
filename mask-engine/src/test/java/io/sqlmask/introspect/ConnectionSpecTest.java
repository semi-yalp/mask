package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    // Connector/J 超时单位是毫秒：connectTimeoutSeconds（秒）×1000、socketTimeout 60s=60000
    assertEquals("jdbc:mysql://127.0.0.1:3306/shop?connectTimeout=10000&socketTimeout=60000&sslMode=DISABLED&allowPublicKeyRetrieval=true",
        new ConnectionSpec("mysql", "127.0.0.1", 3306, "shop", "u", "p",
            List.of(), false, false, "disable", 10).toJdbcUrl());
    assertEquals("jdbc:mysql://h:3306/shop?connectTimeout=10000&socketTimeout=60000&sslMode=REQUIRED&verifyServerCertificate=false",
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p",
            List.of(), false, false, "require", 10).toJdbcUrl());
  }

  @Test
  void trinoUrl() {
    assertEquals("jdbc:trino://127.0.0.1:8080/crm?SSL=false",
        new ConnectionSpec("trino", "127.0.0.1", 8080, "crm", "u", "p",
            List.of(), false, false, "disable", 10).toJdbcUrl());
    // require 必须显式带 SSL=true：trino-jdbc 的 SSL 默认 false，
    // 不写参数等价于明文连接（require 语义静默失效）
    assertEquals("jdbc:trino://h:8080/crm?SSL=true",
        new ConnectionSpec("trino", "h", 8080, "crm", "u", "p",
            List.of(), false, false, "require", 10).toJdbcUrl());
  }

  @Test
  void postgresqlUrlUnchanged() {
    assertEquals("jdbc:postgresql://127.0.0.1:5432/crm"
        + "?sslmode=disable&connectTimeout=10&socketTimeout=60&readOnly=true", minimal().toJdbcUrl());
  }

  @Test
  void rejectsUnsupportedSslmodeForMysqlAndTrino() {
    // mysql/trino 只支持 disable/require；prefer/verify-full 必须报用法错误，
    // 不能静默降级为明文（PG 分支透传驱动处理，不受影响）
    IllegalArgumentException mysqlVerifyFull = assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p",
            List.of(), false, false, "verify-full", 10).toJdbcUrl());
    assertTrue(mysqlVerifyFull.getMessage().contains("unsupported sslmode"));
    IllegalArgumentException mysqlPrefer = assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p",
            List.of(), false, false, "prefer", 10).toJdbcUrl());
    assertTrue(mysqlPrefer.getMessage().contains("unsupported sslmode"));
    IllegalArgumentException trinoPrefer = assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("trino", "h", 8080, "crm", "u", "p",
            List.of(), false, false, "prefer", 10).toJdbcUrl());
    assertTrue(trinoPrefer.getMessage().contains("unsupported sslmode"));
  }
}
