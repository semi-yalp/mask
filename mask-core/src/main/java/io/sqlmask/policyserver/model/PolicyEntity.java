package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;

import java.util.List;

/**
 * A stored policy: kind, enablement, subject selector, target selector and the
 * kind-specific payload — {@code udf} + {@code arguments} for datamask,
 * {@code filterExpr} for row filters.
 */
public record PolicyEntity(String name, PolicyType policyType, boolean enabled,
    ResourceSelector resource, SubjectSelector subjects, String udf,
    List<Object> arguments, String filterExpr) {

  public PolicyEntity {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    subjects = subjects == null
        ? new SubjectSelector(java.util.Set.of("*"), java.util.Set.of())
        : subjects;
  }

  /** Convenience constructor without subjects: the policy applies to everyone. */
  public PolicyEntity(String name, PolicyType policyType, boolean enabled,
      ResourceSelector resource, String udf, List<Object> arguments, String filterExpr) {
    this(name, policyType, enabled, resource, null, udf, arguments, filterExpr);
  }
}
