package io.sqlmask.policy.model;

/**
 * One matched row-filter expression, in the engine's decision order.
 *
 * <p>v3 (2026-09-21): rebuilt in place; contract byte-identical.
 */
public record RowFilterHit(String policyName, String expr) {
}