package io.sqlmask.metaserver.model;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every "any field present but group incomplete" combination of
 * {@link ConnectionInfo#ofNullable}: each single present field must pull the
 * whole group into the completeness check.
 */
class ConnectionInfoBranchesTest {

  private static SqlMaskException partial(Object... only) {
    // slots: host, port, database, dbUser, passwordRef, sslmode, timeout, schemas
    String host = only.length > 0 ? (String) only[0] : null;
    Integer port = only.length > 1 ? (Integer) only[1] : null;
    String database = only.length > 2 ? (String) only[2] : null;
    String dbUser = only.length > 3 ? (String) only[3] : null;
    String passwordRef = only.length > 4 ? (String) only[4] : null;
    String sslmode = only.length > 5 ? (String) only[5] : null;
    Integer timeout = only.length > 6 ? (Integer) only[6] : null;
    @SuppressWarnings("unchecked")
    List<String> schemas = only.length > 7 ? (List<String>) only[7] : null;
    return assertThrows(SqlMaskException.class,
        () -> ConnectionInfo.ofNullable(host, port, database, dbUser, passwordRef,
            sslmode, timeout, schemas, false));
  }

  @Test
  void hostAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, partial("127.0.0.1").getCode());
  }

  @Test
  void portAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, partial(null, 5432).getCode());
  }

  @Test
  void databaseAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, partial(null, null, "db").getCode());
  }

  @Test
  void dbUserAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, partial(null, null, null, "u").getCode());
  }

  @Test
  void passwordRefAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        partial(null, null, null, null, "REF").getCode());
  }

  @Test
  void sslmodeAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        partial(null, null, null, null, null, "require").getCode());
  }

  @Test
  void timeoutAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        partial(null, null, null, null, null, null, 5).getCode());
  }

  @Test
  void schemasAloneIsRejected() {
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        partial(null, null, null, null, null, null, null, List.of("public")).getCode());
  }

  @Test
  void nonPositivePortInsideGroupIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> ConnectionInfo.ofNullable("h", 0, "db", "u", "REF", null, null, null, false));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void completeGroupWithExplicitSslAndTimeoutKeepsThem() {
    ConnectionInfo info = ConnectionInfo.ofNullable("h", 5433, "db", "u", "REF",
        "require", 7, null, true);
    assertEquals("require", info.sslmode());
    assertEquals(7, info.connectTimeoutSeconds());
    assertEquals(List.of(), info.schemas());
  }

  @Test
  void directConstructorNormalizesNullSchemasAndNonPositiveTimeout() {
    ConnectionInfo info = new ConnectionInfo("h", 5432, "db", "u", "REF", " ", 0, null, false);
    assertEquals("disable", info.sslmode());
    assertEquals(10, info.connectTimeoutSeconds());
    assertEquals(List.of(), info.schemas());
  }

  @Test
  void allNullsStillYieldNull() {
    assertNull(ConnectionInfo.ofNullable(null, null, null, null, null, null, null, null, false));
  }
}
