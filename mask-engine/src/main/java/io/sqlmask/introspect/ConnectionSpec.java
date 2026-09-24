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

  /** Masks the password: one log line must never leak the credential. */
  @Override
  public String toString() {
    return "ConnectionSpec[engine=" + engine + ", host=" + host + ", port=" + port
        + ", database=" + database + ", user=" + user + ", password=****, schemas=" + schemas
        + ", includeViews=" + includeViews + ", strict=" + strict + ", sslmode=" + sslmode
        + ", connectTimeoutSeconds=" + connectTimeoutSeconds + "]";
  }

  /** URL carries read-only + timeouts as defense in depth; password goes via the JDBC properties. */
  public String toJdbcUrl() {
    return switch (engine) {
      case "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/" + database
          + "?sslmode=" + sslmode
          + "&connectTimeout=" + connectTimeoutSeconds
          + "&socketTimeout=60&readOnly=true";
      case "mysql" -> {
        requireKnownSslmode();
        // Connector/J takes connect/socket timeouts in milliseconds, unlike
        // the PostgreSQL driver's seconds.
        yield "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?connectTimeout=" + connectTimeoutSeconds * 1000
            + "&socketTimeout=60000"
            + ("require".equalsIgnoreCase(sslmode)
                ? "&sslMode=REQUIRED&verifyServerCertificate=false"
                // caching_sha2_password (MySQL 8 default) needs the server RSA key
                // when TLS is off; the password is already plaintext on a disabled
                // -SSL wire so this adds no new exposure. With sslMode=REQUIRED
                // the key exchange rides the TLS channel and the flag stays off.
                : "&sslMode=DISABLED&allowPublicKeyRetrieval=true");
      }
      case "trino" -> {
        requireKnownSslmode();
        boolean require = "require".equalsIgnoreCase(sslmode);
        // trino-jdbc rejects unrecognized URL properties and has no
        // connectTimeout property; spec.connectTimeoutSeconds is not
        // representable in the Trino URL. SSL defaults to false in the
        // driver, so "require" must say so explicitly or the connection
        // silently stays plaintext (and the password leaks in the clear).
        yield "jdbc:trino://" + host + ":" + port + "/" + database
            + (require ? "?SSL=true" : "?SSL=false");
      }
      default -> throw new IllegalArgumentException("unsupported engine " + engine);
    };
  }

  /**
   * MySQL and Trino branches only understand {@code disable} and {@code
   * require}; anything else (notably {@code prefer}, {@code verify-full}) must
   * fail loudly instead of being silently downgraded to plaintext, per spec.
   * PostgreSQL keeps passing sslmode through to its driver.
   */
  private void requireKnownSslmode() {
    String mode = sslmode.toLowerCase(Locale.ROOT);
    if (!mode.equals("disable") && !mode.equals("require")) {
      throw new IllegalArgumentException("unsupported sslmode '" + sslmode
          + "' for " + engine + " (supported: disable, require)");
    }
  }
}
