package io.masklite.metadata;

import org.apache.calcite.sql.type.SqlTypeName;

import java.util.List;

/**
 * Complete qualified table identity with its ordered column list and an
 * optional static row-filter condition. Column types are parsed once at
 * configuration load time into Calcite {@link SqlTypeName} descriptors so
 * downstream schema construction never re-parses strings.
 */
public record TableMetadata(String catalog, String schema, String name, List<Column> columns,
    String rowFilter) {

  public TableMetadata {
    columns = List.copyOf(columns);
  }

  /** Backward-compatible view of a table without a row filter. */
  public TableMetadata(String catalog, String schema, String name, List<Column> columns) {
    this(catalog, schema, name, columns, null);
  }

  public String qualifiedName() {
    return catalog + "." + schema + "." + name;
  }

  /** Ordered column of a table with its parsed Calcite type. */
  public record Column(String name, SqlTypeName sqlTypeName, Integer precision, Integer scale) {

    public Column {
      if (precision != null && precision < 0) {
        throw new IllegalArgumentException("precision must be >= 0");
      }
      if (scale != null && scale < 0) {
        throw new IllegalArgumentException("scale must be >= 0");
      }
    }
  }
}
