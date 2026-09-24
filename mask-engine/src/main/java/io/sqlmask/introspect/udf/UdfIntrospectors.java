package io.sqlmask.introspect.udf;

import io.sqlmask.error.SqlMaskException;
import java.util.Locale;

/**
 * Registry of engine-specific UDF introspectors, mirroring
 * {@link io.sqlmask.introspect.MetadataIntrospectors}. UDF discovery currently
 * covers the two engines whose masking functions the rewrite emits
 * ({@code postgresql}, {@code mysql}); Trino has no server-side UDF registry
 * to discover, so it is absent on purpose.
 */
public final class UdfIntrospectors {

  private UdfIntrospectors() {
  }

  /**
   * Resolves the introspector by engine name (case-insensitive).
   *
   * <p>An unknown engine is a {@code CONFIG_ERROR}, matching
   * {@code MetadataIntrospectors.byEngine}: the engine string comes from
   * caller configuration (a request body or instance record), so the failure
   * is a malformed configuration rather than a parse or introspection
   * failure.</p>
   */
  public static UdfIntrospector byEngine(String engine) {
    String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "postgresql" -> new PgUdfIntrospector();
      case "mysql" -> new MysqlUdfIntrospector();
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported engine '" + engine + "' (supported: postgresql, mysql)");
    };
  }
}
