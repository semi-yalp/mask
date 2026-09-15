package io.masklite.rewrite;

import io.masklite.config.MaskingConfig;
import io.masklite.config.MaskingPolicy;
import io.masklite.lineage.ColumnOrigin;
import io.masklite.metadata.ColumnKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the masking policy for one output column from its origin columns:
 * a simple column-key lookup with the legacy-stable tie-break — among
 * origins with a bound policy, the lexicographically smallest normalized
 * column key wins.
 */
public final class ColumnMaskSelector {

  private final Map<ColumnKey, MaskingPolicy> bindings;

  private ColumnMaskSelector(Map<ColumnKey, MaskingPolicy> bindings) {
    this.bindings = bindings;
  }

  public static ColumnMaskSelector of(MaskingConfig config) {
    Map<ColumnKey, MaskingPolicy> bindings = new HashMap<>();
    for (MaskingConfig.ColumnPolicyBinding binding : config.columnPolicies()) {
      bindings.put(binding.key(), config.policies().get(binding.policyName()));
    }
    return new ColumnMaskSelector(bindings);
  }

  public Optional<MaskingPolicy> select(Set<ColumnOrigin> origins) {
    ColumnKey bestKey = null;
    MaskingPolicy best = null;
    for (ColumnOrigin origin : origins) {
      ColumnKey key = origin.key();
      MaskingPolicy policy = bindings.get(key);
      if (policy == null) {
        continue;
      }
      if (bestKey == null || ColumnKey.ORDER.compare(key, bestKey) < 0) {
        bestKey = key;
        best = policy;
      }
    }
    return Optional.ofNullable(best);
  }
}
