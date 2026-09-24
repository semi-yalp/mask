package io.sqlmask.metaserver.classification;

import io.sqlmask.error.SqlMaskException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in classification taxonomy: category catalog (with Chinese display
 * meaning), sensitivity levels and the column-name heuristics that back
 * {@code POST /auto}. Heuristics are table-driven and matched in declaration
 * order against the lowered column name (substring containment); the first
 * hit wins.
 */
public final class ClassificationTaxonomy {

  private ClassificationTaxonomy() {
  }

  /** category name -> display meaning, in catalog order. */
  public static final Map<String, String> CATEGORIES = categories();
  public static final List<String> LEVELS = List.of("HIGH", "MEDIUM", "LOW");

  private static Map<String, String> categories() {
    Map<String, String> categories = new LinkedHashMap<>();
    categories.put("IDENTITY", "身份标识");
    categories.put("PII", "个人信息");
    categories.put("CONTACT", "联系方式");
    categories.put("FINANCE", "财务");
    categories.put("LOCATION", "位置");
    categories.put("MEDICAL", "医疗");
    categories.put("OTHER", "其他");
    return java.util.Collections.unmodifiableMap(categories);
  }

  /** name-fragment -> {category, level}, matched in order (first hit wins). */
  private static final List<Rule> RULES = List.of(
      new Rule(new String[] {"phone", "mobile", "tel", "msisdn"}, "CONTACT", "HIGH"),
      new Rule(new String[] {"email", "mail"}, "CONTACT", "MEDIUM"),
      new Rule(new String[] {"id_card", "idcard", "identity", "ssn", "passport"}, "IDENTITY", "HIGH"),
      new Rule(new String[] {"name", "fullname", "first_name", "last_name", "nickname"}, "PII", "MEDIUM"),
      new Rule(new String[] {"birthday", "birth_date", "dob", "age"}, "PII", "MEDIUM"),
      new Rule(new String[] {"address", "addr", "city", "country", "zip", "postal"}, "LOCATION", "LOW"),
      new Rule(new String[] {"salary", "balance", "amount", "fund", "account_no", "card_no", "bank"}, "FINANCE", "HIGH"),
      new Rule(new String[] {"disease", "diagnosis", "medical", "health"}, "MEDICAL", "HIGH"));

  private record Rule(String[] fragments, String category, String level) {
  }

  /** Heuristic guess for one column; empty when no rule matches. */
  public static Optional<Classification> guess(String instance, String columnKey,
      String columnName) {
    String lowered = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);
    for (Rule rule : RULES) {
      for (String fragment : rule.fragments()) {
        if (lowered.contains(fragment)) {
          return Optional.of(new Classification(instance, columnKey, rule.category(),
              rule.level(), Classification.SOURCE_AUTO, null, null));
        }
      }
    }
    return Optional.empty();
  }

  /** Normalizes and validates a category against the catalog. */
  public static String requireCategory(String category) {
    String normalized = normalize(category);
    if (!CATEGORIES.containsKey(normalized)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown category '" + category + "' (legal values: "
              + String.join(", ", CATEGORIES.keySet()) + ")");
    }
    return normalized;
  }

  /** Normalizes and validates a level. */
  public static String requireLevel(String level) {
    String normalized = normalize(level);
    if (!LEVELS.contains(normalized)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown level '" + level + "' (legal values: "
              + String.join(", ", LEVELS) + ")");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "category and level are required");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
