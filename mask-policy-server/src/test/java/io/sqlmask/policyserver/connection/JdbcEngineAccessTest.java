package io.sqlmask.policyserver.connection;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.TableDef;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link JdbcEngineAccess} against a real embedded PostgreSQL. */
class JdbcEngineAccessTest {

  private static EmbeddedPostgres embedded;
  private static JdbcTemplate jdbc;
  private static String host;
  private static int port;
  private static String database;
  private static String user;

  /** Returns a non-blank fake password; the embedded PG runs trust auth so the value is ignored. */
  private static ConnectionResolver resolver() {
    return new ConnectionResolver(name -> "test-password");
  }

  @BeforeAll
  static void start() throws IOException, java.sql.SQLException {
    embedded = EmbeddedPostgres.builder().start();
    DataSource ds = embedded.getPostgresDatabase();
    jdbc = new JdbcTemplate(ds);
    host = "localhost";
    port = embedded.getPort();
    database = "postgres";
    user = "postgres";
    jdbc.execute("CREATE TABLE IF NOT EXISTS suggest_test (phone varchar, amount numeric)");
  }

  @AfterAll
  static void stop() throws IOException {
    if (embedded != null) {
      embedded.close();
    }
  }

  private ConnectionConfig cfg() {
    return new ConnectionConfig("postgresql", host, port, database, user, "TEST_PW",
        List.of(), false, "disable", 15);
  }

  @Test
  void testConnectsToEmbeddedPostgres() {
    JdbcEngineAccess access = new JdbcEngineAccess(resolver());
    ConnectionTestResult result = access.test(cfg());
    assertTrue(result.ok());
    assertEquals("postgresql", result.dialect());
  }

  @Test
  void testUnreachablePortIsConnectionFailed() {
    ConnectionConfig bad = new ConnectionConfig("postgresql", "127.0.0.1", 1, database, user,
        "TEST_PW", List.of(), false, "disable", 5);
    JdbcEngineAccess access = new JdbcEngineAccess(resolver());
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> access.test(bad));
    assertEquals(SqlMaskException.Code.CONNECTION_FAILED, e.getCode());
  }

  @Test
  void missingPasswordIsConnectionFailed() {
    JdbcEngineAccess access = new JdbcEngineAccess(new ConnectionResolver(name -> null));
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> access.test(cfg()));
    assertEquals(SqlMaskException.Code.CONNECTION_FAILED, e.getCode());
  }

  @Test
  void fetchListsPublishedTables() {
    JdbcEngineAccess access = new JdbcEngineAccess(resolver());
    List<TableDef> tables = access.fetch(cfg());
    assertTrue(tables.stream().anyMatch(t -> t.name().toLowerCase(Locale.ROOT)
        .equals("suggest_test")));
    TableDef found = tables.stream()
        .filter(t -> t.name().equalsIgnoreCase("suggest_test")).findFirst().orElseThrow();
    assertTrue(found.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase("phone")));
  }
}