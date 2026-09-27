package io.masklite.policy.model;

import io.masklite.policy.PolicyException;

import java.util.List;

/**
 * One legacy-derived policy: column-level resources plus data-mask items
 * carrying the subject selectors. mask-lite only models data masks — row
 * filters live on {@code TableMetadata.rowFilter} and never pass through
 * here.
 */
public record Policy(String name, boolean enabled, int priority,
    List<PolicyResource> resources, List<DataMaskItem> dataMaskItems) {

  public Policy {
    if (name == null || name.isBlank()) {
      throw new PolicyException("policy name must not be blank");
    }
    resources = resources == null ? List.of() : List.copyOf(resources);
    dataMaskItems = dataMaskItems == null ? List.of() : List.copyOf(dataMaskItems);
    if (resources.isEmpty()) {
      throw new PolicyException("policy '" + name + "': at least one resource is required");
    }
    if (dataMaskItems.isEmpty()) {
      throw new PolicyException(
          "policy '" + name + "': dataMask policies declare dataMaskItems only");
    }
    for (PolicyResource resource : resources) {
      if (resource.column() == null) {
        throw new PolicyException("policy '" + name
            + "': dataMask resources must declare a column level (use \"*\" for all columns)");
      }
    }
  }
}
