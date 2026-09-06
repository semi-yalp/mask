package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * A stored policy: kind, enablement, target selector and the kind-specific
 * payload — {@code udf} + {@code arguments} for datamask, {@code filterExpr}
 * for row filters.
 */
public record PolicyEntity(String name, PolicyType policyType, boolean enabled,
    ResourceSelector resource, String udf, List<Object> arguments, String filterExpr) {

  public PolicyEntity {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}
