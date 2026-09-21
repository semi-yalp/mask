package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;

import java.util.List;
import java.util.Set;

/**
 * A stored policy: operation scope (SELECT only this version), kind,
 * enablement, priority, subject selector, target selector and the
 * kind-specific payload — {@code udf} + {@code arguments} for datamask,
 * {@code filterExpr} for row filters. Higher priority wins when two enabled
 * datamask policies overlap; row filters compose with AND regardless of
 * priority. {@code currentVersion} is granted by the store (starts at 1 on
 * create) and drives optimistic-concurrency checks on update.
 */
public record PolicyEntity(String name, AccessType accessType, PolicyType policyType,
    boolean enabled, Integer priority, ResourceSelector resource, SubjectSelector subjects,
    String udf, List<Object> arguments, String filterExpr, int currentVersion) {

  public PolicyEntity {
    accessType = accessType == null ? AccessType.SELECT : accessType;
    priority = priority == null ? 0 : priority;
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
    subjects = subjects == null
        ? new SubjectSelector(Set.of("*"), Set.of())
        : subjects;
  }

  /** Convenience constructor without the store-granted version (defaults to 0). */
  public PolicyEntity(String name, AccessType accessType, PolicyType policyType, boolean enabled,
      Integer priority, ResourceSelector resource, SubjectSelector subjects, String udf,
      List<Object> arguments, String filterExpr) {
    this(name, accessType, policyType, enabled, priority, resource, subjects, udf, arguments,
        filterExpr, 0);
  }
}