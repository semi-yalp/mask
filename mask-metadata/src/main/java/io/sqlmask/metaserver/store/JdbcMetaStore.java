package io.sqlmask.metaserver.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.model.TableStructure.ColumnStructure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link MetaStore}. Every mutation and its
 * metadata_version bump share one transaction (spec §3 version mechanism).
 */
@Repository
public class JdbcMetaStore implements MetaStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public JdbcMetaStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
  }

  private static final org.springframework.jdbc.core.RowMapper<InstanceRow> INSTANCE_ROW =
      (ResultSet rs, int i) -> new InstanceRow(
          rs.getString("name"),
          rs.getString("dialect"),
          connectionOf(rs),
          rs.getLong("metadata_version"));

  private static ConnectionInfo connectionOf(ResultSet rs) throws SQLException {
    String host = rs.getString("host");
    if (host == null) {
      return null;
    }
    List<String> schemas;
    String schemasJson = rs.getString("schemas");
    try {
      schemas = schemasJson == null ? List.of()
          : JSON.readValue(schemasJson, new TypeReference<List<String>>() {
          });
    } catch (Exception e) {
      throw new IllegalStateException("unreadable schemas JSON", e);
    }
    return new ConnectionInfo(host, rs.getInt("port"), rs.getString("database"),
        rs.getString("db_user"), rs.getString("password_ref"), rs.getString("sslmode"),
        rs.getInt("connect_timeout_seconds"), schemas, rs.getBoolean("include_views"));
  }

  @Override
  public void createInstance(InstanceRow row) {
    ConnectionInfo c = row.connection();
    jdbc.update("""
        INSERT INTO meta_instance (name, dialect, host, port, database, db_user, password_ref,
                                   sslmode, connect_timeout_seconds, schemas, include_views)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
        row.name(), row.dialect(),
        c == null ? null : c.host(), c == null ? null : c.port(),
        c == null ? null : c.database(), c == null ? null : c.dbUser(),
        c == null ? null : c.passwordRef(), c == null ? null : c.sslmode(),
        c == null ? null : c.connectTimeoutSeconds(),
        c == null ? null : toJson(c.schemas()), c != null && c.includeViews());
  }

  @Override
  public Optional<InstanceRow> findInstance(String name) {
    List<InstanceRow> rows = jdbc.query(
        "SELECT name, dialect, host, port, database, db_user, password_ref, sslmode, "
            + "connect_timeout_seconds, schemas::text AS schemas, include_views, metadata_version "
            + "FROM meta_instance WHERE name = ?", INSTANCE_ROW, name);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public List<InstanceRow> listInstances() {
    return jdbc.query(
        "SELECT name, dialect, host, port, database, db_user, password_ref, sslmode, "
            + "connect_timeout_seconds, schemas::text AS schemas, include_views, metadata_version "
            + "FROM meta_instance ORDER BY name", INSTANCE_ROW);
  }

  @Override
  public void updateInstance(String name, ConnectionInfo c) {
    tx.execute(status -> {
      jdbc.update("""
          UPDATE meta_instance SET host = ?, port = ?, database = ?, db_user = ?, password_ref = ?,
                   sslmode = ?, connect_timeout_seconds = ?, schemas = ?::jsonb, include_views = ?,
                   metadata_version = metadata_version + 1, updated_at = now()
          WHERE name = ?
          """,
          c == null ? null : c.host(), c == null ? null : c.port(),
          c == null ? null : c.database(), c == null ? null : c.dbUser(),
          c == null ? null : c.passwordRef(), c == null ? null : c.sslmode(),
          c == null ? null : c.connectTimeoutSeconds(),
          c == null ? null : toJson(c.schemas()), c != null && c.includeViews(), name);
      return null;
    });
  }

  @Override
  public void deleteInstance(String name) {
    jdbc.update("DELETE FROM meta_instance WHERE name = ?", name);
  }

  @Override
  public void replaceStructure(String name, List<TableStructure> tables) {
    tx.execute(status -> {
      Long id = jdbc.queryForObject("SELECT id FROM meta_instance WHERE name = ?", Long.class, name);
      jdbc.update(
          "UPDATE meta_instance SET metadata_version = metadata_version + 1, updated_at = now() "
              + "WHERE id = ?", id);
      jdbc.update(
          "DELETE FROM meta_column WHERE table_id IN "
              + "(SELECT id FROM meta_table WHERE instance_id = ?)", id);
      jdbc.update("DELETE FROM meta_table WHERE instance_id = ?", id);
      int tablePosition = 0;
      for (TableStructure table : tables) {
        Long tableId = jdbc.queryForObject(
            "INSERT INTO meta_table (instance_id, catalog, schema_name, table_name, position) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long.class, id, table.catalog(), table.schema(), table.name(), tablePosition++);
        int columnPosition = 0;
        for (ColumnStructure column : table.columns()) {
          jdbc.update(
              "INSERT INTO meta_column (table_id, name, type_declaration, position) "
                  + "VALUES (?, ?, ?, ?)",
              tableId, column.name(), column.type(), columnPosition++);
        }
      }
      return null;
    });
  }

  @Override
  public List<TableStructure> loadStructure(String name) {
    Long id = jdbc.queryForObject("SELECT id FROM meta_instance WHERE name = ?", Long.class, name);
    List<TableStructure> tables = jdbc.query(
        "SELECT id, catalog, schema_name, table_name FROM meta_table "
            + "WHERE instance_id = ? ORDER BY position", (rs, i) -> new TableStructure(
            rs.getString("catalog"), rs.getString("schema_name"), rs.getString("table_name"),
            jdbc.query(
                "SELECT name, type_declaration FROM meta_column WHERE table_id = ? ORDER BY position",
                (crs, ci) -> new ColumnStructure(crs.getString("name"),
                    crs.getString("type_declaration")),
                rs.getLong("id"))),
        id);
    return tables;
  }

  private static String toJson(List<String> schemas) {
    try {
      return JSON.writeValueAsString(schemas);
    } catch (Exception e) {
      throw new IllegalStateException("unwritable schemas JSON", e);
    }
  }
}
