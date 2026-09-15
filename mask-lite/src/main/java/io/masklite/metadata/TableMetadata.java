package io.masklite.metadata;

import org.apache.calcite.sql.type.SqlTypeName;

import java.util.List;

/**
 * Complete qualified table identity with its ordered column list. Column types
 * are parsed once at configuration load time into Calcite {@link SqlTypeName}
 * descriptors so downstream schema construction never re-parses strings.
 */
public record TableMetadata(String catalog, String schema, String name, List<Column> columns) {

  public TableMetadata {
    columns = List.copyOf(columns);
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
