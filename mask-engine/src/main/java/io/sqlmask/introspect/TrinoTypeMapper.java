package io.sqlmask.introspect;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps Trino {@code information_schema.COLUMNS.data_type} text (e.g.
 * {@code timestamp(3) with time zone}) to a YAML declaration the Trino dialect
 * resolver accepts. Complex types (array/map/row/qdigest...) and unknown names
 * degrade to varchar. Never throws.
 */
public final class TrinoTypeMapper {

  private static final Set<String> ACCEPTED = Set.of(
      "boolean", "tinyint", "smallint", "int", "integer", "bigint", "real", "double",
      "decimal", "char", "character", "varchar", "character varying", "varbinary",
      "date", "time", "timestamp");

  /** Shape the echoed parenthesized parameters must have: {@code digits[, digits]}. */
  private static final Pattern VALID_PARAMS = Pattern.compile("\\d+(\\s*,\\s*\\d+)?");

  public PgTypeMapper.Mapped map(String dataTypeText) {
    if (dataTypeText == null || dataTypeText.isBlank()) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String raw = dataTypeText.trim();
    String base = raw.toLowerCase(Locale.ROOT);
    // strip optional "[(p)]" then optional " with time zone" to learn the base name
    String stripped = base;
    if (stripped.endsWith(" with time zone")) {
      stripped = stripped.substring(0, stripped.length() - " with time zone".length());
    }
    String bare = stripParams(stripped);
    if (!ACCEPTED.contains(bare)) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    boolean parameterized = !bare.equals(stripped);
    if (parameterized && !validParams(stripped)) {
      // Malformed (char ()) or int-overflowing (decimal(99999999999)) parameters
      // would blow up TrinoTypeResolver downstream: degrade instead of echoing
      // a declaration the resolver cannot parse.
      return new PgTypeMapper.Mapped("varchar", true);
    }
    if (!parameterized && (bare.equals("char") || bare.equals("character"))) {
      // resolver defaults to char(1); echo explicitly. A parameterized char(n)
      // keeps its length: silently collapsing it to char(1) would lose data.
      return new PgTypeMapper.Mapped("char(1)", false);
    }
    return new PgTypeMapper.Mapped(raw, false);
  }

  /**
   * True when the outermost parenthesized parameters of {@code text} are
   * {@code digits[, digits]} and every number fits in an {@code int}; the
   * resolver parses them with {@code Integer.valueOf} and would throw otherwise.
   */
  private boolean validParams(String text) {
    int open = trailingOpenParen(text);
    if (open < 0) {
      return false;
    }
    String content = text.substring(open + 1, text.length() - 1);
    if (!VALID_PARAMS.matcher(content).matches()) {
      return false;
    }
    for (String part : content.split(",")) {
      try {
        Integer.valueOf(part.trim());
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return true;
  }

  private String stripParams(String text) {
    int open = trailingOpenParen(text);
    return open < 0 ? text : text.substring(0, open).trim();
  }

  /** Index of the {@code (} matching a trailing {@code )}, or -1 when absent or unbalanced. */
  private static int trailingOpenParen(String text) {
    if (!text.endsWith(")")) {
      return -1;
    }
    int depth = 0;
    for (int i = text.length() - 1; i >= 0; i--) {
      char c = text.charAt(i);
      if (c == ')') depth++;
      else if (c == '(') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }
}
