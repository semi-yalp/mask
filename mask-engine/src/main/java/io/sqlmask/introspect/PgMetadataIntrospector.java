package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Pulls table and column metadata from one PostgreSQL database through a
 * single read-only JDBC connection. Statements only SELECT from pg_catalog;
 * the JDBC URL itself is opened with readOnly=true as defense in depth.
 *
 * <p>Not {@code final}: tests override {@link #open(ConnectionSpec)} with an
 * anonymous subclass to supply a mocked connection.</p>
 */
public class PgMetadataIntrospector implements MetadataIntrospector {

  private static final String CATALOG_SQL = "SELECT current_database()";
  private static final String TABLES_SQL = """
      SELECT n.nspname AS schema_name, c.relname AS table_name, c.relkind,
             a.attname AS column_name, format_type(a.atttypid, a.atttypmod) AS pg_type,
             a.attnum
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
      WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
        AND c.relkind IN (%s)
      ORDER BY n.nspname, c.relname, a.attnum""";

  private final PgTypeMapper typeMapper = new PgTypeMapper();

  @Override
  public IntrospectionResult introspect(ConnectionSpec spec) {
    try (Connection connection = open(spec)) {
      String catalog = queryCurrentDatabase(connection);
      List<String> warnings = new ArrayList<>();
      List<IntrospectionResult.TableInfo> tables = queryTables(connection, spec, catalog, warnings);
      for (IntrospectionResult.TableInfo table : tables) {
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          if (column.degraded()) {
            warnings.add("column " + table.catalog() + "." + table.schema() + "."
                + table.name() + "." + column.name() + ": PG type "
                + column.originalPgType() + " is not representable, degraded to varchar");
          }
        }
      }
      if (tables.isEmpty()) {
        warnings.add("未找到任何表，请检查 schema 过滤条件");
      }
      return new IntrospectionResult(catalog, tables, warnings);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "metadata introspection failed: " + sanitize(e.getMessage()), e);
    }
  }

  /** Overridable so tests can supply a mocked connection. */
  protected Connection open(ConnectionSpec spec) throws SQLException {
    DriverManager.setLoginTimeout(spec.connectTimeoutSeconds() <= 0
        ? 10 : spec.connectTimeoutSeconds());
    Properties props = new Properties();
    props.setProperty("user", spec.user());
    props.setProperty("password", spec.password() == null ? "" : spec.password());
    return DriverManager.getConnection(spec.toJdbcUrl(), props);
  }

  private String queryCurrentDatabase(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CATALOG_SQL);
         ResultSet rs = statement.executeQuery()) {
      if (!rs.next()) {
        throw new SQLException("current_database() returned no row");
      }
      return rs.getString(1);
    }
  }

  private List<IntrospectionResult.TableInfo> queryTables(
      Connection connection, ConnectionSpec spec, String catalog,
      List<String> warnings) throws SQLException {
    String relKinds = spec.includeViews() ? "'r', 'p', 'v', 'm'" : "'r', 'p'";
    List<String> schemas = spec.schemas();
    String sql = TABLES_SQL.formatted(relKinds);
    if (!schemas.isEmpty()) {
      StringBuilder predicate = new StringBuilder();
      for (int i = 0; i < schemas.size(); i++) {
        predicate.append(i == 0 ? "AND n.nspname IN (" : ", ");
        predicate.append("?");
        if (i == schemas.size() - 1) {
          predicate.append(")");
        }
      }
      sql = sql.replace("ORDER BY", predicate + "\nORDER BY");
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (String schema : schemas) {
        statement.setString(index++, schema);
      }
      try (ResultSet rs = statement.executeQuery()) {
        return assemble(rs, catalog, warnings);
      }
    }
  }

  private List<IntrospectionResult.TableInfo> assemble(
      ResultSet rs, String catalog, List<String> warnings) throws SQLException {
    Map<String, IntrospectionResult.TableInfo> byKey = new LinkedHashMap<>();
    Set<String> unknownKindTables = new HashSet<>();
    while (rs.next()) {
      String schema = rs.getString("schema_name");
      String name = rs.getString("table_name");
      String column = rs.getString("column_name");
      String pgType = rs.getString("pg_type");
      PgTypeMapper.Mapped mapped = typeMapper.map(pgType);
      String key = schema + "." + name;
      IntrospectionResult.TableInfo table = byKey.get(key);
      if (table == null) {
        String relKind = rs.getString("relkind");
        String kind = kindOf(relKind);
        if (kind == null) {
          kind = TableKind.TABLE;
          if (unknownKindTables.add(key)) {
            warnings.add("unknown relkind '" + relKind + "' for "
                + catalog + "." + schema + "." + name + ", degraded to table");
          }
        }
        table = new IntrospectionResult.TableInfo(catalog, schema, name, kind, new ArrayList<>());
        byKey.put(key, table);
      }
      table.columns().add(new IntrospectionResult.ColumnInfo(
          column, mapped.yamlType(), pgType, mapped.degraded()));
    }
    return new ArrayList<>(byKey.values());
  }

  /** Maps a pg_class.relkind value; null means unrecognized (caller degrades). */
  private static String kindOf(String relKind) {
    return switch (relKind) {
      case "v" -> TableKind.VIEW;
      case "m" -> TableKind.MATERIALIZED_VIEW;
      case "r", "p" -> TableKind.TABLE;
      default -> null;
    };
  }

  /** Strips anything that may carry connection details from driver messages. */
  private String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:postgresql");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
