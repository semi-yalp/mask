package io.sqlmask.introspect.udf;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/**
 * Pulls the masking-UDF surface (functions + their overload signatures) of one
 * schema/database through JDBC. Mirrors {@link io.sqlmask.introspect.MetadataIntrospector}
 * but for callable functions instead of tables; statements only SELECT from the
 * engine's system catalogs.
 *
 * <p>Two entry points: callers that already hold a connection (the UDF center
 * service reuses one connection for deploy + re-import) use
 * {@link #introspect(Connection, String)}; {@link #introspect(ConnectionSpec)}
 * opens its own read-only connection and delegates, mirroring the metadata
 * introspectors' {@code open} convention.</p>
 */
public interface UdfIntrospector {

  /**
   * Discovers the functions of {@code schemaOrDatabase} (a PostgreSQL schema
   * name, or the connected database for MySQL) over an already-open
   * connection; the caller owns the connection's lifecycle.
   */
  List<UdfSignature> introspect(Connection connection, String schemaOrDatabase) throws SQLException;

  /**
   * Opens a read-only connection from {@code spec} (password via JDBC
   * properties, login timeout set, never logged) and delegates to
   * {@link #introspect(Connection, String)}. The target schema is the first
   * entry of {@code spec.schemas()} when present, otherwise the engine
   * fallback ({@link #fallbackTarget(ConnectionSpec)}). A JDBC failure
   * surfaces as {@code SqlMaskException(INTROSPECT_ERROR)} with connection
   * details stripped, matching the metadata introspectors' contract.
   */
  default List<UdfSignature> introspect(ConnectionSpec spec) {
    String target = spec.schemas().isEmpty() ? fallbackTarget(spec) : spec.schemas().get(0);
    try (Connection connection = open(spec)) {
      return introspect(connection, target);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "udf introspection failed: " + sanitize(e.getMessage()), e);
    }
  }

  /**
   * Target used when {@code spec.schemas()} is empty: MySQL introspects the
   * connected database, PostgreSQL defaults to the {@code public} schema
   * (where the built-in masking templates deploy).
   */
  default String fallbackTarget(ConnectionSpec spec) {
    return spec.database();
  }

  /** Overridable so tests can supply a mocked connection, per the introspect convention. */
  default Connection open(ConnectionSpec spec) throws SQLException {
    DriverManager.setLoginTimeout(spec.connectTimeoutSeconds() <= 0
        ? 10 : spec.connectTimeoutSeconds());
    Properties props = new Properties();
    props.setProperty("user", spec.user());
    props.setProperty("password", spec.password() == null ? "" : spec.password());
    return DriverManager.getConnection(spec.toJdbcUrl(), props);
  }

  /** One discovered overload: parameter types and return type in engine-native text. */
  record UdfSignature(String name, List<String> paramTypes, String returnType) {

    public UdfSignature {
      paramTypes = List.copyOf(paramTypes == null ? List.of() : paramTypes);
    }
  }

  /** Strips anything that may carry connection details from driver messages. */
  private static String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
