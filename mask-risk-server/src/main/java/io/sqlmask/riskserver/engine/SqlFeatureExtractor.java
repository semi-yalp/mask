package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.SensitiveColumn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight SQL feature extraction for risk scoring: detects SELECT *,
 * matches the sensitive-asset registry against table/column tokens, and pulls
 * FROM/JOIN table references. Deliberately regex-based (the heavy Calcite
 * parsing already happened upstream in mask-core); word-boundary matching
 * keeps false positives low for the demo corpus.
 */
public final class SqlFeatureExtractor {

  private static final Pattern SELECT_STAR =
      Pattern.compile("(?is)\\bselect\\s+(distinct\\s+)?\\*");
  private static final Pattern TABLE_REF =
      Pattern.compile("(?is)\\b(from|join)\\s+((\"[^\"]+\"|[A-Za-z_][\\w$]*)(\\s*\\.\\s*(\"[^\"]+\"|[A-Za-z_][\\w$]*))*)");

  /**
   * Enriches a parsed event with selectStar, matchedTables and the sensitive
   * columns it touches (referenced by name, or exposed via SELECT * on a
   * table that owns sensitive columns).
   */
  public RiskEvent enrich(RiskEvent parsed, List<SensitiveColumn> registry) {
    String sql = parsed.originalSql();
    boolean selectStar = sql != null && SELECT_STAR.matcher(sql).find();

    String lower = sql == null ? "" : sql.toLowerCase();
    Set<String> matchedTables = new LinkedHashSet<>();
    if (sql != null) {
      Matcher matcher = TABLE_REF.matcher(sql);
      while (matcher.find()) {
        String ref = matcher.group(2).replace("\"", "").replaceAll("\\s+", "");
        matchedTables.add(ref);
        // Also keep the bare table name so crm.public.customer refs match
        // registry entries keyed by either shape.
        int lastDot = ref.lastIndexOf('.');
        if (lastDot > 0) {
          matchedTables.add(ref.substring(lastDot + 1));
        }
      }
    }

    List<SensitiveColumn> touched = new ArrayList<>();
    Map<String, SensitiveColumn> byColumnKey = new LinkedHashMap<>();
    Map<String, List<SensitiveColumn>> byTableName = new LinkedHashMap<>();
    for (SensitiveColumn column : registry) {
      if (!column.enabled()) {
        continue;
      }
      byColumnKey.put(column.columnKey().toLowerCase(), column);
      byTableName.computeIfAbsent(column.tableName().toLowerCase(), k -> new ArrayList<>())
          .add(column);
    }

    for (SensitiveColumn column : byColumnKey.values()) {
      String table = column.tableName().toLowerCase();
      String col = column.columnName().toLowerCase();
      if (containsToken(lower, table) && containsToken(lower, col)) {
        touched.add(column);
      } else if (selectStar && containsToken(lower, table)) {
        touched.add(column);
      }
    }
    // Registry hits count as matched tables too (fully-qualified keys).
    for (SensitiveColumn column : touched) {
      matchedTables.add(column.tableKey().toLowerCase());
      matchedTables.add(column.tableName().toLowerCase());
    }

    return parsed.toBuilder()
        .selectStar(selectStar)
        .matchedTables(new ArrayList<>(matchedTables))
        .sensitiveColumns(touched)
        .build();
  }

  /** Whole-word, case-insensitive token containment. */
  private static boolean containsToken(String haystackLower, String tokenLower) {
    if (haystackLower.isEmpty() || tokenLower.isEmpty()) {
      return false;
    }
    int idx = haystackLower.indexOf(tokenLower);
    while (idx >= 0) {
      int end = idx + tokenLower.length();
      boolean leftOk = idx == 0 || !isWordChar(haystackLower.charAt(idx - 1));
      boolean rightOk = end == haystackLower.length() || !isWordChar(haystackLower.charAt(end));
      if (leftOk && rightOk) {
        return true;
      }
      idx = haystackLower.indexOf(tokenLower, idx + 1);
    }
    return false;
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
