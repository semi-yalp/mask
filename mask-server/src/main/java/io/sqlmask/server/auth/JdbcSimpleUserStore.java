package io.sqlmask.server.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.auth.AuthException;
import io.sqlmask.auth.Role;
import io.sqlmask.auth.SimpleUser;
import io.sqlmask.auth.SimpleUserStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@code auth_user} table backing the simple auth mode. Groups are stored as
 * a JSON array (portable TEXT, same convention as the other domain stores);
 * the password hash is write-only and never selected into views.
 */
public final class JdbcSimpleUserStore implements SimpleUserStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final RowMapper<SimpleUser> MAPPER = new RowMapper<>() {
    @Override
    public SimpleUser mapRow(ResultSet rs, int rowNum) throws SQLException {
      List<String> groups;
      String raw = rs.getString("groups_json");
      try {
        groups = raw == null || raw.isBlank()
            ? List.of()
            : JSON.readValue(raw, new TypeReference<List<String>>() {
            });
      } catch (java.io.IOException e) {
        throw new IllegalStateException("unreadable groups JSON for user "
            + rs.getString("username"), e);
      }
      return new SimpleUser(rs.getString("username"), rs.getString("display_name"),
          Role.valueOf(rs.getString("role")), groups, rs.getString("password_hash"),
          rs.getBoolean("enabled"));
    }
  };

  private final JdbcTemplate jdbc;

  public JdbcSimpleUserStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SimpleUser create(SimpleUser user) {
    try {
      jdbc.update("INSERT INTO auth_user (username, display_name, role, groups_json, "
              + "password_hash, enabled) VALUES (?, ?, ?, ?, ?, ?)",
          user.username(), user.displayName(), user.role().name(), toJson(user.groups()),
          user.passwordHash(), user.enabled());
      return user;
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          "user '" + user.username() + "' already exists");
    }
  }

  @Override
  public SimpleUser update(SimpleUser user) {
    int updated = jdbc.update("UPDATE auth_user SET display_name = ?, role = ?, groups_json = ?, "
            + "password_hash = ?, enabled = ?, updated_at = now() WHERE username = ?",
        user.displayName(), user.role().name(), toJson(user.groups()), user.passwordHash(),
        user.enabled(), user.username());
    if (updated == 0) {
      throw new AuthException(AuthException.Code.INVALID_CREDENTIALS,
          "user '" + user.username() + "' does not exist");
    }
    return user;
  }

  @Override
  public Optional<SimpleUser> find(String username) {
    return jdbc.query("SELECT * FROM auth_user WHERE username = ?", MAPPER, username)
        .stream().findFirst();
  }

  @Override
  public List<SimpleUser> list() {
    return jdbc.query("SELECT * FROM auth_user ORDER BY username", MAPPER);
  }

  @Override
  public void delete(String username) {
    jdbc.update("DELETE FROM auth_user WHERE username = ?", username);
  }

  private static String toJson(List<String> groups) {
    try {
      return JSON.writeValueAsString(groups == null ? List.of() : groups);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("unreadable groups", e);
    }
  }
}
