package io.sqlmask.policy.match;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.MaskInstruction;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyNames;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.RowFilterHit;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.Subject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The policy decision point (PDP): pure, stateless matching of resources and
 * subjects against the policy index. Deterministic for a given index,
 * resource and subject. Request-side names are concrete values; "*" appears
 * only on the policy declaration side.
 */
public final class PolicyEngine {

  private final PolicyIndex index;

  public PolicyEngine(PolicyIndex index) {
    this.index = index;
  }

  /** Column mask decision: the first matching policy's most specific item, or empty. */
  public Optional<MaskInstruction> maskFor(String catalog, String schema, String table,
      String column, Subject subject) {
    String c = PolicyNames.normalize(catalog, "catalog");
    String s = PolicyNames.normalize(schema, "schema");
    String t = PolicyNames.normalize(table, "table");
    String col = PolicyNames.normalize(column, "column");
    for (Policy policy : index.dataMasks()) {
      if (policy.resources().stream().noneMatch(r -> matches(r, c, s, t, col))) {
        continue;
      }
      DataMaskItem best = null;
      int bestLevel = 0;
      for (DataMaskItem item : policy.dataMaskItems()) {
        int level = item.selector().matchLevel(subject);
        if (level > bestLevel) {
          bestLevel = level;
          best = item;
        }
      }
      if (best != null) {
        return Optional.of(new MaskInstruction(policy.name(), best.udf(), best.arguments()));
      }
    }
    return Optional.empty();
  }

  /**
   * Row filter decision: all matching hits in decision order; AND composition
   * is the caller's job (masking uniqueness does not apply to predicates).
   */
  public List<RowFilterHit> rowFiltersFor(String catalog, String schema, String table,
      Subject subject) {
    String c = PolicyNames.normalize(catalog, "catalog");
    String s = PolicyNames.normalize(schema, "schema");
    String t = PolicyNames.normalize(table, "table");
    List<RowFilterHit> hits = new ArrayList<>();
    for (Policy policy : index.rowFilters()) {
      if (policy.resources().stream().noneMatch(r -> matches(r, c, s, t, null))) {
        continue;
      }
      RowFilterItem best = null;
      int bestLevel = 0;
      for (RowFilterItem item : policy.rowFilterItems()) {
        int level = item.selector().matchLevel(subject);
        if (level > bestLevel) {
          bestLevel = level;
          best = item;
        }
      }
      if (best != null) {
        hits.add(new RowFilterHit(policy.name(), best.filterExpr()));
      }
    }
    return List.copyOf(hits);
  }

  private static boolean matches(PolicyResource r, String c, String s, String t, String column) {
    if (!levelMatches(r.catalog(), c) || !levelMatches(r.schema(), s)
        || !levelMatches(r.table(), t)) {
      return false;
    }
    if (column == null) {
      return r.column() == null;
    }
    return r.column() != null && levelMatches(r.column(), column);
  }

  private static boolean levelMatches(String pattern, String value) {
    return "*".equals(pattern) || pattern.equals(value);
  }
}
