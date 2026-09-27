package io.masklite.config;

import io.masklite.metadata.ColumnKey;
import io.masklite.policy.model.DataMaskItem;
import io.masklite.policy.model.Policy;
import io.masklite.policy.model.PolicyResource;
import io.masklite.policy.model.SubjectSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts the legacy metadata.yaml column policies (policies / columns)
 * into the policy set: every item applies to everyone (subject groups
 * ["*"]), priority 0, enabled, in declaration order. The rewrite output is
 * byte-identical to the legacy registry path.
 *
 * <p>行过滤不经过本适配器：唯一事实来源是元数据里的
 * {@code TableMetadata.rowFilter}，由 {@code RowFilterRegistry.build} 直读。
 */
public final class LegacyPolicyAdapter {

  private LegacyPolicyAdapter() {
  }

  public static List<Policy> convert(MaskingConfig config) {
    List<Policy> policies = new ArrayList<>();
    SubjectSelector everyone = new SubjectSelector(Set.of(), Set.of("*"));
    for (MaskingConfig.ColumnPolicyBinding binding : config.columnPolicies()) {
      ColumnKey key = binding.key();
      MaskingPolicy policy = config.policies().get(binding.policyName());
      policies.add(new Policy(
          binding.policyName(),
          true, 0,
          List.of(PolicyResource.column(key.catalog(), key.schema(), key.table(), key.column(),
              binding.inheritOnCopy())),
          List.of(new DataMaskItem(everyone, policy.udf(), policy.arguments()))));
    }
    return policies;
  }
}
