package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** Trino scalar type declarations. */
public final class TrinoTypeResolver implements TypeResolver {

  private static final String TZ_SUFFIX = " with time zone";

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    boolean withTimeZone = lowered.endsWith(TZ_SUFFIX);
    ParsedType parsed = TypeResolver.split(lowered, withTimeZone ? TZ_SUFFIX : null);
    if (withTimeZone) {
      return timeZoneColumn(name, raw, parsed);
    }
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "boolean" -> requireNoParams(raw);
      case "tinyint", "smallint", "integer", "int", "bigint", "real", "double" -> { }
      case "decimal" -> {
        // 有 precision 无 scale 的 decimal(10) 会在下游 schema 构建
        // (YamlCalciteSchemaFactory#createSqlType 拆箱 scale)时 NPE——这里
        // fail-closed 报支持清单(对齐 Hive I2 修复思路)。bare decimal(双 null)
        // 走无参 createSqlType 分支无 NPE,且是 TrinoTypeMapper 采集映射的
        // 既有合法行为,必须继续放行。
        if (precision != null && scale == null) {
          throw parseError(raw);
        }
      }
      case "char", "character" -> precision = precision != null ? precision : 1;
      case "varchar", "character varying" -> { /* precision optional (unbounded) */ }
      case "varbinary" -> { }
      case "date" -> requireNoParams(raw);
      case "timestamp" -> { /* precision optional */ }
      case "time" -> { /* precision optional */ }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "boolean" -> SqlTypeName.BOOLEAN;
      case "tinyint" -> SqlTypeName.TINYINT;
      case "smallint" -> SqlTypeName.SMALLINT;
      case "integer", "int" -> SqlTypeName.INTEGER;
      case "bigint" -> SqlTypeName.BIGINT;
      case "real" -> SqlTypeName.REAL;
      case "double" -> SqlTypeName.DOUBLE;
      case "decimal" -> SqlTypeName.DECIMAL;
      case "char", "character" -> SqlTypeName.CHAR;
      case "varchar", "character varying" -> SqlTypeName.VARCHAR;
      case "varbinary" -> SqlTypeName.VARBINARY;
      case "date" -> SqlTypeName.DATE;
      case "timestamp" -> SqlTypeName.TIMESTAMP;
      case "time" -> SqlTypeName.TIME;
      default -> throw parseError(raw);
    };
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
  }

  private static TableMetadata.Column timeZoneColumn(String name, String raw, ParsedType parsed) {
    switch (parsed.base()) {
      case "timestamp" -> {
        return new TableMetadata.Column(name, SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
            parsed.precision(), parsed.scale(), raw);
      }
      case "time" -> {
        return new TableMetadata.Column(name, SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE,
            parsed.precision(), parsed.scale(), raw);
      }
      default -> throw parseError(raw);
    }
  }

  private static void requireNoParams(String raw) {
    if (raw.contains("(")) {
      throw parseError(raw);
    }
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported or malformed type declaration '" + raw + "' for dialect trino; "
            + "supported scalar types: boolean, tinyint, smallint, integer/int, bigint, "
            + "real, double, decimal(p,s), char(n), varchar[(n)], varbinary, date, "
            + "time[(p)] [with time zone], timestamp[(p)] [with time zone]");
  }
}
