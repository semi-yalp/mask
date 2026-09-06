package io.sqlmask.introspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable connection parameters for pulling metadata from one database of a
 * supported engine ({@code postgresql}, {@code mysql}, or {@code trino}). The
 * catalog reported in generated YAML equals {@link #database()}.
 */
public record ConnectionSpec(String engine, String host, int port, String database, String user,
    String password, List<String> schemas, boolean includeViews, boolean strict,
    String sslmode, int connectTimeoutSeconds) {

  public ConnectionSpec {
    Objects.requireNonNull(host, "host");
    engine = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("postgresql", "mysql", "trino").contains(engine)) {
      throw new IllegalArgumentException(
          "unsupported engine '" + engine + "' (supported: postgresql, mysql, trino)");
    }
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
    return switch (engine) {
      case "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/" + database
          + "?sslmode=" + sslmode
          + "&connectTimeout=" + connectTimeoutSeconds
          + "&socketTimeout=60&readOnly=true";
      case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/" + database
          + "?connectTimeout=" + connectTimeoutSeconds + "&socketTimeout=60"
          + ("require".equalsIgnoreCase(sslmode)
              ? "&sslMode=REQUIRED&verifyServerCertificate=false"
              : "&sslMode=DISABLED");
      case "trino" -> {
        boolean require = "require".equalsIgnoreCase(sslmode);
        yield "jdbc:trino://" + host + ":" + port + "/" + database
            + (require ? "" : "?SSL=false")
            + (require ? "?" : "&") + "connectTimeout=" + connectTimeoutSeconds + "s";
      }
      default -> throw new IllegalArgumentException("unsupported engine " + engine);
    };
  }
}
