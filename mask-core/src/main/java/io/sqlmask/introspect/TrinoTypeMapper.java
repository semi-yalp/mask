package io.sqlmask.introspect;

import java.util.Locale;
import java.util.Set;

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
    if (!parameterized && (bare.equals("char") || bare.equals("character"))) {
      // resolver defaults to char(1); echo explicitly. A parameterized char(n)
      // keeps its length: silently collapsing it to char(1) would lose data.
      return new PgTypeMapper.Mapped("char(1)", false);
    }
    return new PgTypeMapper.Mapped(raw, false);
  }

  private String stripParams(String text) {
    if (!text.endsWith(")")) {
      return text;
    }
    int depth = 0;
    for (int i = text.length() - 1; i >= 0; i--) {
      char c = text.charAt(i);
      if (c == ')') depth++;
      else if (c == '(') {
        depth--;
        if (depth == 0) {
          return text.substring(0, i).trim();
        }
      }
    }
    return text;
  }
}
