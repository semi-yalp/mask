package io.sqlmask.metaserver.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.List;

class ConnectionInfoTest {

  @Test
  void allBlankYieldsNull() {
    assertNull(ConnectionInfo.ofNullable(null, null, null, null, null, null, null, null, false));
  }

  @Test
  void completeGroupNormalizesDefaults() {
    ConnectionInfo info = ConnectionInfo.ofNullable("127.0.0.1", 5432, "db", "user",
        "REF", "", null, List.of("public"), true);
    assertEquals("disable", info.sslmode());
    assertEquals(10, info.connectTimeoutSeconds());
    assertEquals(List.of("public"), info.schemas());
    assertEquals("REF", info.passwordRef());
  }

  @Test
  void partialGroupRejected() {
    SqlMaskException e = org.junit.jupiter.api.Assertions.assertThrows(SqlMaskException.class,
        () -> ConnectionInfo.ofNullable("127.0.0.1", 5432, "db", null, null, null, null, null, false));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void blankPasswordRefRejectedInsideGroup() {
    SqlMaskException e = org.junit.jupiter.api.Assertions.assertThrows(SqlMaskException.class,
        () -> ConnectionInfo.ofNullable("127.0.0.1", 5432, "db", "user", " ", null, null, null, false));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
