package io.sqlmask.introspect;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps MySQL {@code information_schema.COLUMNS.COLUMN_TYPE} text (e.g.
 * {@code varchar(50)}, {@code int unsigned}) to a YAML type declaration the
 * MySQL dialect resolver accepts. The {@code unsigned} suffix is stripped with
 * a degraded marker (range semantics are lost); anything outside the accepted
 * set — including {@code signed} suffixes — degrades to {@code varchar}. Never
 * throws.
 */
public final class MysqlTypeMapper {

  private static final Pattern SHAPE = Pattern.compile(
      "^([a-z ]+?)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?(?:\\s+(unsigned|signed))?$");
  private static final Set<String> ACCEPTED = Set.of(
      "tinyint", "smallint", "mediumint", "int", "integer", "bigint",
      "decimal", "dec", "numeric", "float", "double", "double precision",
      "char", "varchar", "tinytext", "text", "mediumtext", "longtext",
      "binary", "varbinary", "date", "datetime", "time", "timestamp");
  private static final Set<String> NO_PARAMS = Set.of(
      "tinytext", "text", "mediumtext", "longtext", "date");

  public PgTypeMapper.Mapped map(String columnTypeText) {
    if (columnTypeText == null || columnTypeText.isBlank()) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String raw = columnTypeText.trim().toLowerCase(Locale.ROOT);
    Matcher m = SHAPE.matcher(raw);
    if (!m.matches()) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String base = m.group(1).trim();
    if (!ACCEPTED.contains(base)) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String suffix = m.group(4);
    if ("signed".equals(suffix)) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    Integer p = parseInt(m.group(2));
    if (p == null && m.group(2) != null) {
      // Digits present but overflow int (e.g. varchar(99999999999)): unsupported
      // shape, not a crash — degrade instead of violating the never-throw contract.
      return new PgTypeMapper.Mapped("varchar", true);
    }
    Integer s = parseInt(m.group(3));
    if (s == null && m.group(3) != null) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    if (NO_PARAMS.contains(base) && (p != null || s != null)) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    boolean unsigned = suffix != null;
    String yamlType = unsigned ? raw.substring(0, m.start(4)).trim() : raw;
    if (base.equals("char") && p == null) {
      yamlType = "char(1)";
    }
    return new PgTypeMapper.Mapped(yamlType, unsigned);
  }

  /**
   * Returns the parsed digits, or {@code null} when absent or too large for
   * {@code int}; callers degrade on the overflow case to keep {@link #map}
   * total (never throws).
   */
  private Integer parseInt(String digits) {
    if (digits == null) {
      return null;
    }
    try {
      return Integer.valueOf(digits);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
