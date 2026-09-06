package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Pulls table/column metadata from one Trino catalog via information_schema.
 * The catalog reported equals the one named in the connection URL
 * ({@code spec.database()}); no title query exists in Trino for this purpose
 * at the connection level. Read-only: the only statement is a SELECT against
 * information_schema.
 *
 * <p>Not {@code final}: tests override {@link #open(ConnectionSpec)} with an
 * anonymous subclass to supply a mocked connection.</p>
 */
public class TrinoMetadataIntrospector implements MetadataIntrospector {

  private static final String TABLES_SQL = """
      SELECT c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.ORDINAL_POSITION
      FROM information_schema.columns c
      JOIN information_schema.tables t
        ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
      WHERE %s
        AND t.TABLE_TYPE IN (%s)
      ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION""";

  private final TrinoTypeMapper typeMapper = new TrinoTypeMapper();

  @Override
  public IntrospectionResult introspect(ConnectionSpec spec) {
    try (Connection connection = open(spec)) {
      List<IntrospectionResult.TableInfo> tables = queryTables(connection, spec, spec.database());
      List<String> warnings = new ArrayList<>();
      for (IntrospectionResult.TableInfo table : tables) {
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          if (column.degraded()) {
            warnings.add("column " + table.catalog() + "." + table.schema() + "."
                + table.name() + "." + column.name() + ": trino type "
                + column.originalPgType() + " is not representable, degraded to varchar");
          }
        }
      }
      if (tables.isEmpty()) {
        warnings.add("未找到任何表，请检查 schema 过滤条件");
      }
      return new IntrospectionResult(spec.database(), tables, warnings);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "metadata introspection failed: " + sanitize(e.getMessage()), e);
    }
  }

  /** Overridable so tests can supply a mocked connection. */
  protected Connection open(ConnectionSpec spec) throws SQLException {
    return DriverManager.getConnection(spec.toJdbcUrl(), connectionProperties(spec));
  }

  /**
   * Trino JDBC refuses to transmit a password over plain HTTP ("TLS/SSL is
   * required for authentication with username and password"), so with TLS off
   * the connection is passwordless regardless of the CLI placeholder password;
   * with {@code sslmode=require} the password rides the TLS channel as usual.
   */
  Properties connectionProperties(ConnectionSpec spec) {
    Properties props = new Properties();
    props.setProperty("user", spec.user());
    if ("require".equalsIgnoreCase(spec.sslmode())) {
      props.setProperty("password", spec.password() == null ? "" : spec.password());
    }
    return props;
  }

  private List<IntrospectionResult.TableInfo> queryTables(
      Connection connection, ConnectionSpec spec, String catalog) throws SQLException {
    String schemaPredicate = spec.schemas().isEmpty()
        ? "1 = 1"
        : "c.TABLE_SCHEMA IN (" + placeholders(spec.schemas().size()) + ")";
    String relKinds = spec.includeViews() ? "'BASE TABLE', 'VIEW'" : "'BASE TABLE'";
    String sql = TABLES_SQL.formatted(schemaPredicate, relKinds);
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (String schema : spec.schemas()) {
        statement.setString(index++, schema);
      }
      try (ResultSet rs = statement.executeQuery()) {
        return assemble(rs, catalog);
      }
    }
  }

  private String placeholders(int n) {
    return String.join(", ", Collections.nCopies(n, "?"));
  }

  private List<IntrospectionResult.TableInfo> assemble(ResultSet rs, String catalog)
      throws SQLException {
    Map<String, IntrospectionResult.TableInfo> byKey = new LinkedHashMap<>();
    while (rs.next()) {
      String schema = rs.getString("TABLE_SCHEMA");
      String name = rs.getString("TABLE_NAME");
      String column = rs.getString("COLUMN_NAME");
      String dataType = rs.getString("DATA_TYPE");
      PgTypeMapper.Mapped mapped = typeMapper.map(dataType);
      String key = schema + "." + name;
      IntrospectionResult.TableInfo table = byKey.get(key);
      if (table == null) {
        table = new IntrospectionResult.TableInfo(catalog, schema, name, new ArrayList<>());
        byKey.put(key, table);
      }
      table.columns().add(new IntrospectionResult.ColumnInfo(column, mapped.yamlType(),
          dataType, mapped.degraded()));
    }
    return new ArrayList<>(byKey.values());
  }

  /** Strips anything that may carry connection details from driver messages. */
  private String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
