package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;

import java.util.List;

/**
 * A stored policy: kind, enablement, priority, subject selector, target
 * selector and the kind-specific payload — {@code udf} + {@code arguments}
 * for datamask, {@code filterExpr} for row filters. Higher priority wins
 * when two enabled datamask policies overlap; row filters compose with AND
 * regardless of priority.
 */
public record PolicyEntity(String name, PolicyType policyType, boolean enabled,
    Integer priority, ResourceSelector resource, SubjectSelector subjects, String udf,
    List<Object> arguments, String filterExpr) {

  public PolicyEntity {
    priority = priority == null ? 0 : priority;
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    subjects = subjects == null
        ? new SubjectSelector(java.util.Set.of("*"), java.util.Set.of())
        : subjects;
  }

  /** Legacy-arity constructor (pre-priority call sites): priority defaults to 0. */
  public PolicyEntity(String name, PolicyType policyType, boolean enabled,
      ResourceSelector resource, SubjectSelector subjects, String udf,
      List<Object> arguments, String filterExpr) {
    this(name, policyType, enabled, 0, resource, subjects, udf, arguments, filterExpr);
  }

  /** Convenience constructor without subjects: the policy applies to everyone. */
  public PolicyEntity(String name, PolicyType policyType, boolean enabled,
      ResourceSelector resource, String udf, List<Object> arguments, String filterExpr) {
    this(name, policyType, enabled, 0, resource, null, udf, arguments, filterExpr);
  }
}
