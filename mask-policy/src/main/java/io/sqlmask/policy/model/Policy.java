package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.List;

/**
 * One Ranger-style policy: resources plus items of exactly one kind
 * (dataMask or rowFilter); the items carry the subject selectors.
 */
public record Policy(String name, boolean enabled, int priority, PolicyType type,
    List<PolicyResource> resources, List<DataMaskItem> dataMaskItems,
    List<RowFilterItem> rowFilterItems) {

  public Policy {
    if (name == null || name.isBlank()) {
      throw new PolicyException("policy name must not be blank");
    }
    if (type == null) {
      throw new PolicyException("policy '" + name + "': type is required");
    }
    resources = resources == null ? List.of() : List.copyOf(resources);
    dataMaskItems = dataMaskItems == null ? List.of() : List.copyOf(dataMaskItems);
    rowFilterItems = rowFilterItems == null ? List.of() : List.copyOf(rowFilterItems);
    if (resources.isEmpty()) {
      throw new PolicyException("policy '" + name + "': at least one resource is required");
    }
    if (type == PolicyType.DATA_MASK) {
      if (dataMaskItems.isEmpty() || !rowFilterItems.isEmpty()) {
        throw new PolicyException(
            "policy '" + name + "': dataMask policies declare dataMaskItems only");
      }
      for (PolicyResource resource : resources) {
        if (resource.column() == null) {
          throw new PolicyException("policy '" + name
              + "': dataMask resources must declare a column level (use \"*\" for all columns)");
        }
      }
    } else {
      if (rowFilterItems.isEmpty() || !dataMaskItems.isEmpty()) {
        throw new PolicyException(
            "policy '" + name + "': rowFilter policies declare rowFilterItems only");
      }
      for (PolicyResource resource : resources) {
        if (resource.column() != null) {
          throw new PolicyException("policy '" + name
              + "': rowFilter resources are table-level; remove the column level");
        }
      }
    }
  }
}
