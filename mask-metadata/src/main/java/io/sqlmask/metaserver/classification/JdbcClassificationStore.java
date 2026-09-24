package io.sqlmask.metaserver.classification;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link ClassificationStore} over the
 * {@code meta_classification} table (see {@code metadata-schema.sql}).
 * Update-then-insert keeps the upsert portable across PostgreSQL and H2 —
 * no {@code ON CONFLICT} dialect involved.
 */
public class JdbcClassificationStore implements ClassificationStore {

  private final JdbcTemplate jdbc;

  public JdbcClassificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<Classification> ROW = (ResultSet rs, int i) -> new Classification(
      rs.getString("instance"),
      rs.getString("column_key"),
      rs.getString("category"),
      rs.getString("level"),
      rs.getString("source"),
      rs.getString("note"),
      instantOf(rs, "updated_at"));

  private static Instant instantOf(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  @Override
  public boolean upsert(Classification classification) {
    Instant now = classification.updatedAt() == null ? Instant.now() : classification.updatedAt();
    int updated = jdbc.update("""
        UPDATE meta_classification SET category = ?, level = ?, source = ?, note = ?, updated_at = ?
        WHERE instance = ? AND column_key = ?
        """,
        classification.category(), classification.level(), classification.source(),
        classification.note(), Timestamp.from(now), classification.instance(),
        classification.columnKey());
    if (updated > 0) {
      return false;
    }
    try {
      jdbc.update("""
          INSERT INTO meta_classification (instance, column_key, category, level, source, note, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """,
          classification.instance(), classification.columnKey(), classification.category(),
          classification.level(), classification.source(), classification.note(),
          Timestamp.from(now));
      return true;
    } catch (DuplicateKeyException lostRace) {
      // concurrent upsert inserted the row first: converge onto an update
      jdbc.update("""
          UPDATE meta_classification SET category = ?, level = ?, source = ?, note = ?, updated_at = ?
          WHERE instance = ? AND column_key = ?
          """,
          classification.category(), classification.level(), classification.source(),
          classification.note(), Timestamp.from(now), classification.instance(),
          classification.columnKey());
      return false;
    }
  }

  @Override
  public List<Classification> find(String instance) {
    return jdbc.query("SELECT instance, column_key, category, level, source, note, updated_at "
        + "FROM meta_classification WHERE instance = ? ORDER BY column_key", ROW, instance);
  }

  @Override
  public Optional<Classification> find(String instance, String columnKey) {
    List<Classification> rows = jdbc.query(
        "SELECT instance, column_key, category, level, source, note, updated_at "
            + "FROM meta_classification WHERE instance = ? AND column_key = ?", ROW,
        instance, columnKey);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  @Override
  public boolean delete(String instance, String columnKey) {
    return jdbc.update("DELETE FROM meta_classification WHERE instance = ? AND column_key = ?",
        instance, columnKey) > 0;
  }

  @Override
  public List<Classification> listAll() {
    return jdbc.query("SELECT instance, column_key, category, level, source, note, updated_at "
        + "FROM meta_classification ORDER BY instance, column_key", ROW);
  }
}
