package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** PostgreSQL scalar type declarations (the historical tool vocabulary). */
public final class PostgresqlTypeResolver implements TypeResolver {

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    ParsedType parsed = TypeResolver.split(lowered, null);
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
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
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
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
}
