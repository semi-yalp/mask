package io.sqlmask.server.grant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** JDBC storage of the unified grant entries. */
@Repository
public class GrantStore {

  private static final RowMapper<GrantEntry> MAPPER = (ResultSet rs, int i) -> new GrantEntry(
      rs.getLong("id"),
      rs.getString("instance"),
      GrantEntry.PrincipalType.valueOf(rs.getString("principal_type")),
      rs.getString("principal"),
      GrantEntry.ResourceType.valueOf(rs.getString("resource_type")),
      rs.getString("resource_id"),
      GrantEntry.Privilege.valueOf(rs.getString("privilege")),
      rs.getString("granted_by"),
      rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbc;

  public GrantStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public GrantEntry insert(GrantEntry entry) {
    org.springframework.jdbc.support.KeyHolder keys =
        new org.springframework.jdbc.support.GeneratedKeyHolder();
    jdbc.update(con -> {
      var ps = con.prepareStatement(
          "INSERT INTO grant_entry (instance, principal_type, principal, resource_type, "
              + "resource_id, privilege, granted_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
          java.sql.Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, entry.instance());
      ps.setString(2, entry.principalType().name());
      ps.setString(3, entry.principal());
      ps.setString(4, entry.resourceType().name());
      ps.setString(5, entry.resourceId());
      ps.setString(6, entry.privilege().name());
      ps.setString(7, entry.grantedBy());
      return ps;
    }, keys);
    long id;
    if (!keys.getKeyList().isEmpty()) {
      Object key = keys.getKeyList().get(0).values().iterator().next();
      id = ((Number) key).longValue();
    } else {
      throw new IllegalStateException("grant insert produced no key");
    }
    return new GrantEntry(id, entry.instance(), entry.principalType(), entry.principal(),
        entry.resourceType(), entry.resourceId(), entry.privilege(), entry.grantedBy(),
        java.time.Instant.now());
  }

  public List<GrantEntry> find(String instance, String principalType, String principal) {
    return jdbc.query(
        "SELECT * FROM grant_entry WHERE instance = ? AND principal_type = ? AND principal = ?"
            + " ORDER BY resource_type, resource_id, privilege",
        MAPPER, instance, principalType, principal);
  }

  public List<GrantEntry> listByInstance(String instance) {
    return jdbc.query("SELECT * FROM grant_entry WHERE instance = ?"
            + " ORDER BY principal_type, principal, resource_type, resource_id",
        MAPPER, instance);
  }

  public List<GrantEntry> listAll() {
    return jdbc.query("SELECT * FROM grant_entry ORDER BY instance, principal, resource_id", MAPPER);
  }

  public Optional<GrantEntry> findExact(String instance, GrantEntry.PrincipalType principalType,
      String principal, GrantEntry.ResourceType resourceType, String resourceId,
      GrantEntry.Privilege privilege) {
    List<GrantEntry> rows = jdbc.query(
        "SELECT * FROM grant_entry WHERE instance = ? AND principal_type = ? AND principal = ?"
            + " AND resource_type = ? AND resource_id = ? AND privilege = ?",
        MAPPER, instance, principalType.name(), principal, resourceType.name(), resourceId,
        privilege.name());
    return rows.stream().findFirst();
  }

  public boolean delete(long id) {
    return jdbc.update("DELETE FROM grant_entry WHERE id = ?", id) > 0;
  }
}
