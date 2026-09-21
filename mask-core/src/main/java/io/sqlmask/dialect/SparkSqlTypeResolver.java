package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** Spark SQL scalar type declarations (spec batch-2 §3); complex types are rejected. */
public final class SparkSqlTypeResolver implements TypeResolver {

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    ParsedType parsed = TypeResolver.split(lowered, null);
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "tinyint" -> { }
      case "smallint" -> { }
      case "int", "integer" -> { }
      case "bigint" -> { }
      case "float" -> { }
      case "double" -> { }
      case "decimal" -> {
        // decimal 声明必须 (p,s) 双精度：单参 decimal(10) 会让 scale=null 流到下游
        // schema 构建处拆箱 NPE，这里 fail-closed 报支持清单（spec 批2 §3 decimal(p,s)）
        if (precision == null || scale == null) {
          throw parseError(raw);
        }
      }
      case "string" -> { }
      case "varchar", "character varying" -> { /* precision optional */ }
      case "char", "character" -> precision = precision != null ? precision : 1;
      case "boolean" -> { }
      case "date" -> { }
      case "timestamp" -> { }
      case "binary" -> { }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "tinyint" -> SqlTypeName.TINYINT;
      case "smallint" -> SqlTypeName.SMALLINT;
      case "int", "integer" -> SqlTypeName.INTEGER;
      case "bigint" -> SqlTypeName.BIGINT;
      case "float" -> SqlTypeName.FLOAT;
      case "double" -> SqlTypeName.DOUBLE;
      case "decimal" -> SqlTypeName.DECIMAL;
      case "string" -> SqlTypeName.VARCHAR;
      case "varchar", "character varying" -> SqlTypeName.VARCHAR;
      case "char", "character" -> SqlTypeName.CHAR;
      case "boolean" -> SqlTypeName.BOOLEAN;
      case "date" -> SqlTypeName.DATE;
      case "timestamp" -> SqlTypeName.TIMESTAMP;
      case "binary" -> SqlTypeName.VARBINARY;
      default -> throw new IllegalStateException("unreachable");
    };
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported sparksql type '" + raw + "' "
            + "(supported: tinyint, smallint, int, bigint, float, double, decimal(p,s), "
            + "string, varchar(n), char(n), boolean, date, timestamp, binary)");
  }
}
