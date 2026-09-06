package io.sqlmask.config;

import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.MaskingPolicy;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts the legacy metadata.yaml policy sections (policies / columns /
 * rowFilter) into an equivalent policy set: every item applies to everyone
 * (subject groups ["*"]), priority 0, enabled, in declaration order. The
 * rewrite output is byte-identical to the legacy registry path.
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
          binding.policyName() + ":" + key,
          true, 0, PolicyType.DATA_MASK,
          List.of(PolicyResource.column(key.catalog(), key.schema(), key.table(), key.column())),
          List.of(new DataMaskItem(everyone, policy.udf(), policy.arguments())),
          List.of()));
    }
    for (TableMetadata table : config.tables()) {
      if (table.rowFilter() == null) {
        continue;
      }
      policies.add(new Policy(
          "rowFilter:" + ColumnKey.normalize(table.catalog(), "catalog") + "."
              + ColumnKey.normalize(table.schema(), "schema") + "."
              + ColumnKey.normalize(table.name(), "table"),
          true, 0, PolicyType.ROW_FILTER,
          List.of(PolicyResource.table(
              ColumnKey.normalize(table.catalog(), "catalog"),
              ColumnKey.normalize(table.schema(), "schema"),
              ColumnKey.normalize(table.name(), "table"))),
          List.of(),
          List.of(new RowFilterItem(everyone, table.rowFilter()))));
    }
    return policies;
  }
}
