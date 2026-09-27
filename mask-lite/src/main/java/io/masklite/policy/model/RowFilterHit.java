package io.masklite.policy.model;

/** One matched row-filter expression, in the engine's decision order. */
public record RowFilterHit(String policyName, String expr) {
}
