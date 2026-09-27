package io.masklite.policy.match;

import io.masklite.policy.model.DataMaskItem;
import io.masklite.policy.model.MaskInstruction;
import io.masklite.policy.model.Policy;
import io.masklite.policy.model.PolicyNames;
import io.masklite.policy.model.PolicyResource;
import io.masklite.policy.model.Subject;

import java.util.Optional;

/**
 * The policy decision point (PDP): pure, stateless matching of resources and
 * subjects against the policy index. Deterministic for a given index,
 * resource and subject. Request-side names are concrete values; glob patterns
 * (each "*" matches any sequence within one level) appear only on the policy
 * declaration side.
 *
 * <p>mask-lite 只做列脱敏决策；行过滤的唯一事实来源是元数据
 * {@code TableMetadata.rowFilter}（{@code RowFilterRegistry.build} 直读），
 * 不经过本引擎。
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
    for (Policy policy : index.policies()) {
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
    return GlobMatcher.matches(pattern, value);
  }
}
