package io.sqlmask.policy.match;

import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic decision order over enabled policies: higher priority first,
 * ties keep declaration order (stable sort). Disabled policies are dropped.
 */
public record PolicyIndex(List<Policy> dataMasks, List<Policy> rowFilters) {

  public PolicyIndex {
    dataMasks = List.copyOf(dataMasks);
    rowFilters = List.copyOf(rowFilters);
  }

  public static PolicyIndex of(List<Policy> policies) {
    List<Policy> ordered = policies.stream()
        .filter(Policy::enabled)
        .sorted(Comparator.comparingInt(Policy::priority).reversed())
        .toList();
    return new PolicyIndex(
        ordered.stream().filter(p -> p.type() == PolicyType.DATA_MASK).toList(),
        ordered.stream().filter(p -> p.type() == PolicyType.ROW_FILTER).toList());
  }
}
