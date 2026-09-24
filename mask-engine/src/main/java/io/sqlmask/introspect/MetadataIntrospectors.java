package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import java.util.Locale;

/** Registry of engine-specific introspectors, mirroring {@code DialectProfiles}. */
public final class MetadataIntrospectors {

  private MetadataIntrospectors() {
  }

  public static MetadataIntrospector byEngine(String engine) {
    String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "postgresql" -> new PgMetadataIntrospector();
      case "mysql" -> new MysqlMetadataIntrospector();
      case "trino" -> new TrinoMetadataIntrospector();
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported engine '" + engine
              + "' (supported: postgresql, mysql, trino)");
    };
  }
}
