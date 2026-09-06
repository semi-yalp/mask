package io.sqlmask.config;

import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.MaskingPolicy;

import java.util.List;
import java.util.Map;

/**
 * Validated YAML model: complete table metadata, per-column policy bindings
 * and named policy definitions, in declaration order.
 */
public record MaskingConfig(
    List<TableMetadata> tables,
    List<ColumnPolicyBinding> columnPolicies,
    Map<String, MaskingPolicy> policies) {

  public MaskingConfig {
    tables = List.copyOf(tables);
    columnPolicies = List.copyOf(columnPolicies);
    policies = Map.copyOf(policies);
  }

  /** A single {@code columns:} entry binding a fully qualified column to a policy name. */
  public record ColumnPolicyBinding(ColumnKey key, String policyName) {
  }
}
