package io.sqlmask.query.executors;

import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;

import java.util.Locale;
import java.util.regex.Pattern;

/** Engine catalog: execution engine to rewrite dialect and JDBC URL rules.
 * URL rules mirror mask-core's ConnectionSpec.toJdbcUrl(). Host, port and
 * database go through a character whitelist before being concatenated into a
 * URL: they come from the metadata store, and a compromised entry must not be
 * able to smuggle extra JDBC parameters (e.g. {@code preferQueryMode=simple}).
 *
 * <p>Timeouts: connect/socket timeouts ride the URL wherever the driver
 * supports them (PostgreSQL seconds, Connector/J and HiveServer2 milliseconds);
 * Trino's 446 driver has no such properties, so {@code DriverManager
 * .setLoginTimeout} (set by the connection factory) is the connect backstop
 * and the statement timeout plus cancel own the query lifecycle. Socket
 * timeouts sit slightly above the statement timeout so a half-open connection
 * cannot pin a worker (and a per-instance permit) until the container timeout.
 * MySQL/StarRocks add useCursorFetch=true so setFetchSize takes effect
 * (对齐 spec §7.4). */
public enum QueryEngine {
  POSTGRESQL("postgresql", "postgresql", 5432),
  MYSQL("mysql", "mysql", 3306),
  STARROCKS("starrocks", "mysql", 9030),
  TRINO("trino", "trino", 8080),
  HIVE("hive", "hive", 10000),
  SPARKSQL("sparksql", "sparksql", 10000);

  /** Letters, digits, dot, underscore, dash: no whitespace or / ? & ; # @ : —
   * nothing that could start another URL token or host component. */
  private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._\\-]+");

  private final String id;
  private final String dialect;
  private final int defaultPort;

  QueryEngine(String id, String dialect, int defaultPort) {
    this.id = id;
    this.dialect = dialect;
    this.defaultPort = defaultPort;
  }

  public String id() { return id; }
  public String dialect() { return dialect; }
  public int defaultPort() { return defaultPort; }

  public static QueryEngine of(String engine) {
    String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    for (QueryEngine candidate : values()) {
      if (candidate.id.equals(normalized)) {
        return candidate;
      }
    }
    throw new QueryException(QueryException.UNSUPPORTED_ENGINE,
        "unsupported engine '" + engine + "' (supported: postgresql, mysql, starrocks, trino, hive, sparksql)");
  }

  public String jdbcUrl(ConnectionView c, int queryTimeoutSeconds) {
    requireSegment(c.host(), "host", false);
    requireSegment(c.database(), "database", this == HIVE || this == SPARKSQL);
    String sslmode = c.sslmode() == null || c.sslmode().isBlank() ? "disable" : c.sslmode();
    int cs = c.connectTimeoutSeconds() <= 0 ? 10 : c.connectTimeoutSeconds();
    // slightly above the statement timeout, in each driver's own unit
    int socketSec = Math.max(1, queryTimeoutSeconds + 5);
    int socketMs = socketSec * 1000;
    return switch (this) {
      case POSTGRESQL -> {
        requireKnownSslmode(sslmode);
        yield "jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + c.database()
            + "?sslmode=" + sslmode + "&connectTimeout=" + cs
            + "&socketTimeout=" + socketSec + "&readOnly=true";
      }
      case MYSQL, STARROCKS -> {
        requireKnownSslmode(sslmode);
        String mode = sslmode.toLowerCase(Locale.ROOT);
        yield "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database()
            + "?connectTimeout=" + (cs * 1000) + "&socketTimeout=" + socketMs
            + ("require".equals(mode)
                ? "&sslMode=REQUIRED&verifyServerCertificate=false"
                : "&sslMode=DISABLED&allowPublicKeyRetrieval=true")
            + "&useCursorFetch=true";
      }
      case TRINO -> {
        requireKnownSslmode(sslmode);
        String mode = sslmode.toLowerCase(Locale.ROOT);
        yield "jdbc:trino://" + c.host() + ":" + c.port() + "/" + c.database()
            + ("require".equals(mode) ? "?SSL=true" : "?SSL=false");
      }
      case HIVE, SPARKSQL -> {
        requireKnownSslmode(sslmode);
        String ssl = sslmode.toLowerCase(Locale.ROOT);
        String db = c.database() == null ? "" : c.database();
        yield "jdbc:hive2://" + c.host() + ":" + c.port() + "/" + db
            + ("require".equals(ssl) ? ";ssl=true" : "")
            + "?connectTimeout=" + (cs * 1000) + "&socketTimeout=" + socketMs;
      }
    };
  }

  private void requireKnownSslmode(String sslmode) {
    String mode = sslmode.toLowerCase(Locale.ROOT);
    if (!mode.equals("disable") && !mode.equals("require")) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "unsupported sslmode '" + sslmode + "' for " + id + " (supported: disable, require)");
    }
  }

  private static void requireSegment(String value, String what, boolean allowEmpty) {
    if (value == null || value.isEmpty()) {
      if (allowEmpty) {
        return;
      }
      throw new QueryException(QueryException.CONFIG_ERROR, what + " is required");
    }
    if (!SAFE_SEGMENT.matcher(value).matches()) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "unsupported " + what + " '" + value + "': only letters, digits, '.', '_', '-' are allowed");
    }
  }
}
