package io.sqlmask.introspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable connection parameters for pulling metadata from one PostgreSQL
 * database. The catalog reported in generated YAML equals {@link #database()}
 * (PostgreSQL has exactly one database per connection).
 */
public record ConnectionSpec(String host, int port, String database, String user,
    String password, List<String> schemas, boolean includeViews, boolean strict,
    String sslmode, int connectTimeoutSeconds) {

  public ConnectionSpec {
    Objects.requireNonNull(host, "host");
    if (database == null || database.isBlank()) {
      throw new IllegalArgumentException("database is required");
    }
    if (user == null || user.isBlank()) {
      throw new IllegalArgumentException("user is required");
    }
    if (port <= 0) {
      throw new IllegalArgumentException("port must be positive");
    }
    schemas = List.copyOf(schemas == null ? List.of() : schemas);
    sslmode = sslmode == null || sslmode.isBlank() ? "disable" : sslmode;
  }

  /**
   * Returns a fresh mutable copy of the schema names. The record keeps an
   * immutable {@code List.copyOf} snapshot internally; handing out a copy on
   * every read is what makes the record immutable while still letting callers
   * manipulate the list they receive without ever touching this spec.
   */
  @Override
  public List<String> schemas() {
    return new ArrayList<>(schemas);
  }

  /** URL carries read-only + timeouts as defense in depth; password goes via the JDBC properties. */
  public String toJdbcUrl() {
    return "jdbc:postgresql://" + host + ":" + port + "/" + database
        + "?sslmode=" + sslmode
        + "&connectTimeout=" + connectTimeoutSeconds
        + "&socketTimeout=60&readOnly=true";
  }
}
