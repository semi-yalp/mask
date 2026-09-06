package io.sqlmask.config;

import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.PolicyRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Result of loading and validating the YAML configuration: the validated
 * model plus the derived policy index.
 */
public record LoadedConfig(MaskingConfig config, PolicyRegistry policyRegistry) {

  public LoadedConfig {
    java.util.Objects.requireNonNull(config);
    java.util.Objects.requireNonNull(policyRegistry);
  }

  public List<TableMetadata> tables() {
    return config.tables();
  }

  /** Finds a table by normalized qualified name {@code catalog.schema.table}. */
  public Optional<TableMetadata> findTable(String catalog, String schema, String table) {
    String wantedCatalog = ColumnKey.normalize(catalog, "catalog");
    String wantedSchema = ColumnKey.normalize(schema, "schema");
    String wantedTable = ColumnKey.normalize(table, "table");
    return config.tables().stream()
        .filter(t -> ColumnKey.normalize(t.catalog(), "catalog").equals(wantedCatalog)
            && ColumnKey.normalize(t.schema(), "schema").equals(wantedSchema)
            && ColumnKey.normalize(t.name(), "table").equals(wantedTable))
        .findFirst();
  }

  static String normalizePart(String part) {
    return part.trim().toLowerCase(Locale.ROOT);
  }
}
