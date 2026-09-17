package io.sqlmask.query.executors;

import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;

import java.util.Locale;

/** Engine catalog: execution engine to rewrite dialect and JDBC URL rules.
 * URL rules mirror mask-core's ConnectionSpec.toJdbcUrl() minus socketTimeout
 * — statement timeout plus cancel own the query lifecycle. */
public enum QueryEngine {
  POSTGRESQL("postgresql", "postgresql", 5432),
  MYSQL("mysql", "mysql", 3306),
  STARROCKS("starrocks", "mysql", 9030),
  TRINO("trino", "trino", 8080);

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
        "unsupported engine '" + engine + "' (supported: postgresql, mysql, starrocks, trino)");
  }

  public String jdbcUrl(ConnectionView c) {
    String sslmode = c.sslmode() == null || c.sslmode().isBlank() ? "disable" : c.sslmode();
    int cs = c.connectTimeoutSeconds() <= 0 ? 10 : c.connectTimeoutSeconds();
    return switch (this) {
      case POSTGRESQL -> "jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + c.database()
          + "?sslmode=" + sslmode + "&connectTimeout=" + cs + "&readOnly=true";
      case MYSQL, STARROCKS -> {
        String mode = sslmode.toLowerCase(Locale.ROOT);
        if (!mode.equals("disable") && !mode.equals("require")) {
          throw new QueryException(QueryException.CONFIG_ERROR,
              "unsupported sslmode '" + sslmode + "' for " + id + " (supported: disable, require)");
        }
        yield "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database()
            + "?connectTimeout=" + (cs * 1000)
            + ("require".equals(mode)
                ? "&sslMode=REQUIRED&verifyServerCertificate=false"
                : "&sslMode=DISABLED&allowPublicKeyRetrieval=true");
      }
      case TRINO -> {
        String mode = sslmode.toLowerCase(Locale.ROOT);
        if (!mode.equals("disable") && !mode.equals("require")) {
          throw new QueryException(QueryException.CONFIG_ERROR,
              "unsupported sslmode '" + sslmode + "' for trino (supported: disable, require)");
        }
        yield "jdbc:trino://" + c.host() + ":" + c.port() + "/" + c.database()
            + ("require".equals(mode) ? "?SSL=true" : "?SSL=false");
      }
    };
  }
}
