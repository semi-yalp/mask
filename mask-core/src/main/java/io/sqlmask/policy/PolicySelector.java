package io.sqlmask.policy;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.metadata.ColumnKey;

import java.util.Optional;
import java.util.Set;

/**
 * Picks the single masking policy applied to one output column when its
 * origin set contains one or more configured base columns.
 *
 * <p>First version: the matching origin with the lexicographically smallest
 * normalized {@link ColumnKey} wins. The rule is stable for a given input
 * and independent of YAML declaration order or expression traversal order;
 * a priority-based selector can replace this class later.
 */
public final class PolicySelector {

  private final PolicyRegistry registry;

  public PolicySelector(PolicyRegistry registry) {
    this.registry = registry;
  }

  public Optional<MaskingPolicy> select(Set<ColumnOrigin> origins) {
    ColumnKey bestKey = null;
    Optional<MaskingPolicy> bestPolicy = Optional.empty();
    for (ColumnOrigin origin : origins) {
      Optional<MaskingPolicy> policy = registry.find(origin.key());
      if (policy.isEmpty()) {
        continue;
      }
      if (bestKey == null || ColumnKey.ORDER.compare(origin.key(), bestKey) < 0) {
        bestKey = origin.key();
        bestPolicy = policy;
      }
    }
    return bestPolicy;
  }
}
