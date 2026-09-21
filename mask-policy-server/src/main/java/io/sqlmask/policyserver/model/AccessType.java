package io.sqlmask.policyserver.model;

/**
 * The operation scope a policy governs. Only {@code SELECT} is supported this
 * version; unknown values are rejected at deserialization, never silently
 * mapped. An absent value defaults to {@code SELECT}.
 */
public enum AccessType {
  SELECT
}