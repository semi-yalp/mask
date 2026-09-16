package io.sqlmask.introspect;

import java.util.List;

/**
 * Metadata pulled from one PostgreSQL database: the catalog name, every
 * captured table with its columns, and human-readable warnings (degraded
 * types, empty selection). Immutable once assembled.
 */
public record IntrospectionResult(String catalog, List<TableInfo> tables, List<String> warnings) {

  public IntrospectionResult {
    tables = List.copyOf(tables);
    warnings = List.copyOf(warnings);
  }

  /**
   * One captured table. {@code kind} is the normalized {@link TableKind}
   * value. {@code columns} intentionally keeps the list the introspector
   * appends to while assembling — no defensive copy here, because
   * {@code List.copyOf} would reject the adds; the snapshot in the enclosing
   * {@link IntrospectionResult} compact constructor is what freezes the result.
   */
  public record TableInfo(String catalog, String schema, String name, String kind,
      List<ColumnInfo> columns) {

    public TableInfo {
      kind = kind == null || kind.isBlank() ? TableKind.TABLE : kind;
    }

    /** Convenience constructor for plain tables (kind defaults to "table"). */
    public TableInfo(String catalog, String schema, String name, List<ColumnInfo> columns) {
      this(catalog, schema, name, TableKind.TABLE, columns);
    }
  }

  /** One column with its mapped YAML type and the original PG type it came from. */
  public record ColumnInfo(String name, String yamlType, String originalPgType, boolean degraded) {
  }
}
