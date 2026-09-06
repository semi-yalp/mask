package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** MySQL scalar type declarations. */
public final class MysqlTypeResolver implements TypeResolver {

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    ParsedType parsed = TypeResolver.split(lowered, null);
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "boolean", "bool" -> { /* BOOLEAN = tinyint(1) alias */ }
      case "tinyint", "smallint" -> { /* display width ignored */ }
      case "mediumint", "int", "integer" -> { }
      case "bigint" -> { }
      case "decimal", "dec", "numeric" -> {
        if (scale != null && precision == null) {
          throw parseError(raw);
        }
      }
      case "float" -> { }
      case "double", "double precision" -> { }
      case "char" -> precision = precision != null ? precision : 1;
      case "varchar" -> { /* (n) customary; tolerated without */ }
      case "tinytext", "text", "mediumtext", "longtext" -> requireNoParams(raw);
      case "binary" -> precision = precision != null ? precision : 1;
      case "varbinary" -> { }
      case "date" -> requireNoParams(raw);
      case "datetime" -> { /* precision optional */ }
      case "timestamp" -> { /* precision optional */ }
      case "time" -> { /* precision optional */ }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "boolean", "bool" -> SqlTypeName.BOOLEAN;
      case "tinyint" -> SqlTypeName.TINYINT;
      case "smallint" -> SqlTypeName.SMALLINT;
      case "mediumint", "int", "integer" -> SqlTypeName.INTEGER;
      case "bigint" -> SqlTypeName.BIGINT;
      case "decimal", "dec", "numeric" -> SqlTypeName.DECIMAL;
      case "float" -> SqlTypeName.REAL;
      case "double", "double precision" -> SqlTypeName.DOUBLE;
      case "char" -> SqlTypeName.CHAR;
      case "varchar" -> SqlTypeName.VARCHAR;
      case "tinytext", "text", "mediumtext", "longtext" -> SqlTypeName.VARCHAR;
      case "binary" -> SqlTypeName.BINARY;
      case "varbinary" -> SqlTypeName.VARBINARY;
      case "date" -> SqlTypeName.DATE;
      case "datetime" -> SqlTypeName.TIMESTAMP;
      case "timestamp" -> SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
      case "time" -> SqlTypeName.TIME;
      default -> throw parseError(raw);
    };
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
  }

  private static void requireNoParams(String raw) {
    if (raw.contains("(")) {
      throw parseError(raw);
    }
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported or malformed type declaration '" + raw + "' for dialect mysql; "
            + "supported scalar types: boolean, tinyint[(n)], smallint[(n)], mediumint, "
            + "int/integer, bigint, decimal(p,s), float, double, char[(n)], varchar(n), "
            + "tinytext/text/mediumtext/longtext, binary[(n)], varbinary(n), date, "
            + "datetime[(p)], timestamp[(p)], time[(p)] "
            + "(json/year/enum/set/bit/geometry are not supported)");
  }
}
