package io.sqlmask.policyserver.connection;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.policyserver.model.ConnectionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionResolverTest {

  private static final ConnectionConfig CFG = new ConnectionConfig("postgresql", "db.internal",
      5432, "shop", "svc", "SHOP_PW", List.of("public", "sales"), true, "require", 30);

  @Test
  void toSpecMapsAllFieldsAndResolvesPassword() {
    ConnectionResolver resolver = new ConnectionResolver(name -> "s3cr3t");
    ConnectionSpec spec = resolver.toSpec(CFG);
    assertEquals("postgresql", spec.engine());
    assertEquals("db.internal", spec.host());
    assertEquals(5432, spec.port());
    assertEquals("shop", spec.database());
    assertEquals("svc", spec.user());
    assertEquals("s3cr3t", spec.password());
    assertEquals(List.of("public", "sales"), spec.schemas());
    assertTrue(spec.includeViews());
    assertEquals("require", spec.sslmode());
    assertEquals(30, spec.connectTimeoutSeconds());
  }

  @Test
  void nullTimeoutDefaultsToFifteen() {
    ConnectionResolver resolver = new ConnectionResolver(name -> "pw");
    ConnectionConfig noTimeout = new ConnectionConfig("postgresql", "h", 1, "d", "u", "P",
        List.of(), false, "disable", null);
    assertEquals(15, resolver.toSpec(noTimeout).connectTimeoutSeconds());
  }

  @Test
  void missingPasswordRefIsConnectionFailed() {
    ConnectionResolver resolver = new ConnectionResolver(name -> null);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> resolver.toSpec(CFG));
    assertEquals(SqlMaskException.Code.CONNECTION_FAILED, e.getCode());
    assertTrue(e.getMessage().contains("SHOP_PW"));
  }

  @Test
  void nullPasswordRefFieldIsConnectionFailed() {
    ConnectionResolver resolver = new ConnectionResolver(name -> "pw");
    ConnectionConfig noRef = new ConnectionConfig("postgresql", "h", 1, "d", "u", null,
        List.of(), false, "disable", null);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> resolver.toSpec(noRef));
    assertEquals(SqlMaskException.Code.CONNECTION_FAILED, e.getCode());
    assertTrue(e.getMessage().contains("passwordRef"));
  }
}