package io.sqlmask.metadata;

import io.sqlmask.dialect.PostgresqlTypeResolver;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Complete qualified table identity with its ordered column list and optional
 * row filter condition. Column types are parsed once at configuration load
 * time into Calcite {@link SqlTypeName} descriptors so downstream schema
 * construction never re-parses strings.
 *
 * @param rowFilter static boolean condition applied to every read of this
 *                  table (see the row-filter design); blank means unconfigured
 */
public record TableMetadata(String catalog, String schema, String name, List<Column> columns,
    String rowFilter) {

  public TableMetadata {
    columns = List.copyOf(columns);
    rowFilter = rowFilter == null || rowFilter.isBlank() ? null : rowFilter;
  }

  /** Convenience constructor for tables without a row filter. */
  public TableMetadata(String catalog, String schema, String name, List<Column> columns) {
    this(catalog, schema, name, columns, null);
  }

  public String qualifiedName() {
    return catalog + "." + schema + "." + name;
  }

  /** Ordered column of a table with its parsed Calcite type. */
  public record Column(String name, SqlTypeName sqlTypeName, Integer precision, Integer scale,
      String declaration) {

    public Column {
      if (precision != null && precision < 0) {
        throw new IllegalArgumentException("precision must be >= 0");
      }
      if (scale != null && scale < 0) {
        throw new IllegalArgumentException("scale must be >= 0");
      }
      declaration = declaration == null || declaration.isBlank() ? null : declaration;
    }

    /** Convenience constructor without an echoed declaration. */
    public Column(String name, SqlTypeName sqlTypeName, Integer precision, Integer scale) {
      this(name, sqlTypeName, precision, scale, null);
    }

    /** Echoes the declared type text when present, else reconstructs it. */
    public String typeDeclaration() {
      if (declaration != null) {
        return declaration;
      }
      String base = switch (sqlTypeName) {
        case BOOLEAN -> "boolean";
        case SMALLINT -> "smallint";
        case INTEGER -> "integer";
        case BIGINT -> "bigint";
        case REAL -> "real";
        case DOUBLE -> "double precision";
        case DECIMAL -> "decimal";
        case CHAR -> "char";
        case VARCHAR -> "varchar";
        case DATE -> "date";
        case TIMESTAMP -> "timestamp";
        case TIMESTAMP_WITH_LOCAL_TIME_ZONE -> "timestamptz";
        case TIME -> "time";
        case TIME_WITH_LOCAL_TIME_ZONE -> "timetz";
        default -> sqlTypeName.getName().toLowerCase(Locale.ROOT);
      };
      if (precision == null) {
        return base;
      }
      if (scale == null) {
        return base + "(" + precision + ")";
      }
      return base + "(" + precision + "," + scale + ")";
    }
  }

  /** Kept for compatibility: delegates to the PostgreSQL resolver. */
  public static Column parseColumn(String name, String typeDeclaration) {
    return new PostgresqlTypeResolver().parseColumn(name, typeDeclaration);
  }

  /** Convenience for building tests and fixtures. */
  public static TableMetadata of(String catalog, String schema, String name, String[][] nameAndTypes) {
    List<Column> columns = new ArrayList<>();
    for (String[] nameAndType : nameAndTypes) {
      columns.add(parseColumn(nameAndType[0], nameAndType[1]));
    }
    return new TableMetadata(catalog, schema, name, columns);
  }
}
