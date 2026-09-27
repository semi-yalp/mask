package io.masklite.policy.match;

import io.masklite.policy.model.Policy;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministic decision order over enabled (data-mask) policies: higher
 * priority first, ties keep declaration order (stable sort). Disabled
 * policies are dropped.
 */
public record PolicyIndex(List<Policy> policies) {

  public PolicyIndex {
    policies = List.copyOf(policies);
  }

  public static PolicyIndex of(List<Policy> policies) {
    return new PolicyIndex(policies.stream()
        .filter(Policy::enabled)
        .sorted(Comparator.comparingInt(Policy::priority).reversed())
        .toList());
  }
}
