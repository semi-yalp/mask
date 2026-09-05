package io.sqlmask.metadata;

import java.util.Comparator;
import java.util.Locale;

/**
 * Fully qualified, normalized column identity
 * {@code catalog.schema.table.column} used as the exact policy index key.
 *
 * <p>Normalization follows the PostgreSQL unquoted-identifier convention
 * (fold to lower case), which matches the Calcite lexical configuration used
 * by the PostgreSQL dialect. The same normalization is applied to names
 * arriving from YAML and from lineage analysis so both sides agree.
 */
public record ColumnKey(String catalog, String schema, String table, String column) {

  public static ColumnKey of(String catalog, String schema, String table, String column) {
    return new ColumnKey(
        normalize(catalog, "catalog"),
        normalize(schema, "schema"),
        normalize(table, "table"),
        normalize(column, "column"));
  }

  /** PostgreSQL unquoted-identifier normalization. */
  public static String normalize(String identifier, String partName) {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException(partName + " identifier must not be blank");
    }
    return identifier.toLowerCase(Locale.ROOT);
  }

  /** Stable comparator over normalized keys, used for deterministic selection. */
  public static final Comparator<ColumnKey> ORDER = Comparator
      .comparing(ColumnKey::catalog)
      .thenComparing(ColumnKey::schema)
      .thenComparing(ColumnKey::table)
      .thenComparing(ColumnKey::column);

  @Override
  public String toString() {
    return catalog + "." + schema + "." + table + "." + column;
  }
}
