package io.sqlmask.policyserver.connection;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataIntrospectors;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.TableDef;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * JDBC-backed {@link EngineAccess} for {@code postgresql}/{@code mysql}/
 * {@code trino}: connectivity via a throwaway {@code DriverManager} connection
 * with a {@code SELECT 1} probe, metadata via mask-core's
 * {@link MetadataIntrospectors}. The password is resolved at call time from
 * {@code ConnectionConfig.passwordRef}; failures are reported as
 * {@code CONNECTION_FAILED} with diagnostics that never include the password.
 */
public final class JdbcEngineAccess implements EngineAccess {

  private final ConnectionResolver resolver;

  public JdbcEngineAccess() {
    this(new ConnectionResolver());
  }

  public JdbcEngineAccess(ConnectionResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public ConnectionTestResult test(ConnectionConfig cfg) {
    long start = System.nanoTime();
    ConnectionSpec spec = resolver.toSpec(cfg);
    Properties props = new Properties();
    props.setProperty("user", spec.user());
    props.setProperty("password", spec.password());
    try (Connection conn = DriverManager.getConnection(spec.toJdbcUrl(), props)) {
      try (var statement = conn.createStatement()) {
        statement.executeQuery("SELECT 1");
      }
    } catch (SQLException | IllegalArgumentException e) {
      throw connectionFailed(cfg, e);
    }
    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    return new ConnectionTestResult(true, cfg.dialect(), latencyMs, List.of());
  }

  @Override
  public List<TableDef> fetch(ConnectionConfig cfg) {
    ConnectionSpec spec = resolver.toSpec(cfg);
    IntrospectionResult result;
    try {
      result = MetadataIntrospectors.byEngine(cfg.dialect()).introspect(spec);
    } catch (SqlMaskException e) {
      // Introspectors report connection/catalog failures as INTROSPECT_ERROR;
      // from the policy server's view that is a failed engine access.
      if (e.getCode() == SqlMaskException.Code.INTROSPECT_ERROR) {
        throw connectionFailed(cfg, e);
      }
      throw e;
    }
    return result.tables().stream()
        .map(ti -> new TableDef(ti.catalog(), ti.schema(), ti.name(),
            ti.columns().stream()
                .map(ci -> new ColumnDef(ci.name(), ci.yamlType()))
                .toList()))
        .toList();
  }

  private static SqlMaskException connectionFailed(ConnectionConfig cfg, Exception cause) {
    return new SqlMaskException(SqlMaskException.Code.CONNECTION_FAILED,
        "cannot access " + cfg.dialect() + " engine at " + cfg.host() + ":" + cfg.port() + "/"
            + cfg.database() + ": " + cause.getMessage(), cause);
  }
}