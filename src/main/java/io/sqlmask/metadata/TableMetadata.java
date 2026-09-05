package io.sqlmask.metadata;

import io.sqlmask.error.SqlMaskException;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Complete qualified table identity with its ordered column list. Column
 * types are parsed once at configuration load time into Calcite
 * {@link SqlTypeName} descriptors so downstream schema construction never
 * re-parses strings.
 */
public record TableMetadata(String catalog, String schema, String name, List<Column> columns) {

  private static final Pattern TYPED = Pattern.compile("^(.+?)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");

  public TableMetadata {
    columns = List.copyOf(columns);
  }

  public ColumnKey tableKey() {
    return ColumnKey.of(catalog, schema, name, "");
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

    /** Reconstructs the PostgreSQL-style type declaration for editors/APIs. */
    public String typeDeclaration() {
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

  /**
   * Parses a PostgreSQL-style scalar type declaration into a Calcite type
   * descriptor. Supported forms: {@code boolean}, integer variants
   * ({@code smallint}, {@code integer}, {@code bigint}), floating types
   * ({@code real}, {@code double precision}, {@code float}), {@code
   * decimal(p,s)}/{@code numeric(p,s)}, {@code char(n)}, {@code varchar(n)},
   * {@code text}, {@code date}, {@code timestamp[(p)]} and
   * {@code timestamp with time zone}. Unknown types are rejected.
   */
  public static Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    String base;
    Integer precision = null;
    Integer scale = null;
    Matcher matcher = TYPED.matcher(lowered);
    if (matcher.matches()) {
      base = matcher.group(1).trim();
      precision = Integer.valueOf(matcher.group(2));
      if (matcher.group(3) != null) {
        scale = Integer.valueOf(matcher.group(3));
      }
    } else {
      base = lowered;
    }
    switch (base) {
      case "boolean", "bool" -> requireNoParams(lowered, "boolean");
      case "smallint", "int2" -> requireNoParams(lowered, "smallint");
      case "integer", "int", "int4" -> requireNoParams(lowered, "integer");
      case "bigint", "int8" -> requireNoParams(lowered, "bigint");
      case "real", "float4" -> requireNoParams(lowered, "real");
      case "double precision", "double", "float8", "float" -> requireNoParams(lowered, "double precision");
      case "decimal", "numeric" -> {
        if (scale != null && precision == null) {
          throw parseError(raw);
        }
      }
      case "char", "character" -> precision = precision != null ? precision : 1;
      case "varchar", "character varying" -> { /* precision optional */ }
      case "text" -> { /* unbounded */ }
      case "date" -> requireNoParams(lowered, "date");
      case "timestamp" -> { /* precision optional */ }
      case "timestamptz", "timestamp with time zone" -> { /* precision optional */ }
      case "time" -> { /* precision optional */ }
      case "timetz", "time with time zone" -> { /* precision optional */ }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "boolean", "bool" -> SqlTypeName.BOOLEAN;
      case "smallint", "int2" -> SqlTypeName.SMALLINT;
      case "integer", "int", "int4" -> SqlTypeName.INTEGER;
      case "bigint", "int8" -> SqlTypeName.BIGINT;
      case "real", "float4" -> SqlTypeName.REAL;
      case "double precision", "double", "float8", "float" -> SqlTypeName.DOUBLE;
      case "decimal", "numeric" -> SqlTypeName.DECIMAL;
      case "char", "character" -> SqlTypeName.CHAR;
      case "varchar", "character varying", "text" -> SqlTypeName.VARCHAR;
      case "date" -> SqlTypeName.DATE;
      case "timestamp" -> SqlTypeName.TIMESTAMP;
      case "timestamptz", "timestamp with time zone" -> SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
      case "time" -> SqlTypeName.TIME;
      case "timetz", "time with time zone" -> SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE;
      default -> throw parseError(raw);
    };
    return new Column(name, typeName, precision, scale);
  }

  private static void requireNoParams(String raw, String canonical) {
    if (raw.contains("(")) {
      throw parseError(raw);
    }
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR,
        "unsupported or malformed type declaration '" + raw + "'; supported scalar types: "
            + "boolean, smallint, integer, bigint, real, double precision, decimal(p,s)/numeric(p,s), "
            + "char(n), varchar(n), text, date, timestamp[(p)], timestamp with time zone, time[(p)]");
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
