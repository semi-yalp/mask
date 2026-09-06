package io.sqlmask.introspect;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps PostgreSQL {@code format_type()} output (e.g. {@code character varying(50)},
 * {@code timestamp(3) with time zone}) to a type declaration the YAML metadata
 * accepts. Anything outside the supported set degrades to {@code varchar} with a
 * {@code degraded} marker so callers can warn — columns never disappear.
 */
public final class PgTypeMapper {

  public record Mapped(String yamlType, boolean degraded) {
  }

  private static final Pattern SHAPE = Pattern.compile(
      "^(.*?)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?$");

  public Mapped map(String formatType) {
    if (formatType == null || formatType.isBlank()) {
      return new Mapped("varchar", true);
    }
    String raw = formatType.trim();
    if (raw.endsWith("[]")) {
      return new Mapped("varchar", true);
    }
    boolean withTimeZone = false;
    String body = raw;
    if (body.endsWith("with time zone")) {
      withTimeZone = true;
      body = body.substring(0, body.length() - "with time zone".length()).trim();
    } else if (body.endsWith("without time zone")) {
      body = body.substring(0, body.length() - "without time zone".length()).trim();
    }
    Matcher m = SHAPE.matcher(body);
    if (!m.matches()) {
      return new Mapped("varchar", true);
    }
    String base = m.group(1).trim().toLowerCase(Locale.ROOT);
    Integer p;
    Integer s;
    try {
      p = m.group(2) == null ? null : Integer.valueOf(m.group(2));
      s = m.group(3) == null ? null : Integer.valueOf(m.group(3));
    } catch (NumberFormatException e) {
      // Precision that overflows int (e.g. numeric(99999999999)) is unsupported
      // shape, not a crash: degrade instead of violating the never-throw contract.
      return new Mapped("varchar", true);
    }
    return switch (base) {
      case "boolean" -> new Mapped("boolean", false);
      case "smallint" -> new Mapped("smallint", false);
      case "integer" -> new Mapped("integer", false);
      case "bigint" -> new Mapped("bigint", false);
      case "real" -> new Mapped("real", false);
      case "double precision" -> new Mapped("double precision", false);
      case "numeric", "decimal" -> new Mapped(p == null ? "numeric"
          : s == null ? "numeric(" + p + ")" : "numeric(" + p + "," + s + ")", false);
      case "character", "char", "bpchar" -> new Mapped("char(" + (p == null ? 1 : p) + ")", false);
      case "character varying", "varchar" -> new Mapped(p == null ? "varchar" : "varchar(" + p + ")", false);
      case "text" -> new Mapped("text", false);
      case "date" -> new Mapped("date", false);
      case "timestamp" -> new Mapped(withTimeZone
          ? (p == null ? "timestamptz" : "timestamptz(" + p + ")")
          : (p == null ? "timestamp" : "timestamp(" + p + ")"), false);
      case "time" -> new Mapped(withTimeZone
          ? (p == null ? "timetz" : "timetz(" + p + ")")
          : (p == null ? "time" : "time(" + p + ")"), false);
      default -> new Mapped("varchar", true);
    };
  }
}
