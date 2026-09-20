package io.sqlmask.policy.model;

/**
 * A policy declares items of exactly one kind.
 *
 * <p>v3 (2026-09-21): rebuilt in place; contract byte-identical.
 */
public enum PolicyType {
  DATA_MASK, ROW_FILTER
}