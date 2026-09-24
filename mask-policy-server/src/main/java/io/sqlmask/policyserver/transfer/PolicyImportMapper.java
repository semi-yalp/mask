package io.sqlmask.policyserver.transfer;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.ResourceSelector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Import direction of the policies.yaml exchange: engine-plane
 * {@link Policy} (multiple resources, multiple items) back to management
 * entities. The management plane stores one resource plus one selector plus
 * one payload per policy, so the mapping is strictly 1:1 for the shape the
 * export side produces: exactly one item and resources confined to a single
 * table (columns are merged in declaration order). Anything richer fails
 * with a message telling the user to split the policy.
 */
public final class PolicyImportMapper {

  public List<PolicyEntity> toEntities(List<Policy> policies) {
    List<PolicyEntity> entities = new ArrayList<>(policies.size());
    for (Policy policy : policies) {
      entities.add(toEntity(policy));
    }
    return entities;
  }

  private PolicyEntity toEntity(Policy policy) {
    if (policy.type() == PolicyType.DATA_MASK) {
      if (policy.dataMaskItems().size() != 1) {
        throw new PolicyException("policy '" + policy.name() + "': declares "
            + policy.dataMaskItems().size()
            + " dataMaskItems; import requires exactly one item per policy (split the policy first)");
      }
      DataMaskItem item = policy.dataMaskItems().get(0);
      return new PolicyEntity(policy.name(), io.sqlmask.policyserver.model.PolicyType.DATAMASK,
          policy.enabled(), policy.priority(), tableResource(policy), item.selector(), item.udf(),
          item.arguments(), null);
    }
    if (policy.rowFilterItems().size() != 1) {
      throw new PolicyException("policy '" + policy.name() + "': declares "
          + policy.rowFilterItems().size()
          + " rowFilterItems; import requires exactly one item per policy (split the policy first)");
    }
    RowFilterItem item = policy.rowFilterItems().get(0);
    return new PolicyEntity(policy.name(), io.sqlmask.policyserver.model.PolicyType.ROW_FILTER,
        policy.enabled(), policy.priority(), tableResource(policy), item.selector(), null, null,
        item.filterExpr());
  }

  /** One entity per table: columns are the distinct column-level resource names, in order. */
  private static ResourceSelector tableResource(Policy policy) {
    String catalog = null;
    String schema = null;
    String table = null;
    Set<String> seen = new LinkedHashSet<>();
    List<String> columns = new ArrayList<>();
    for (PolicyResource resource : policy.resources()) {
      if (catalog == null) {
        catalog = resource.catalog();
        schema = resource.schema();
        table = resource.table();
      } else if (!catalog.equals(resource.catalog()) || !schema.equals(resource.schema())
          || !table.equals(resource.table())) {
        throw new PolicyException("policy '" + policy.name()
            + "': resources span multiple tables; import requires a single table per policy"
            + " (split the policy first)");
      }
      if (resource.column() != null && seen.add(resource.column())) {
        columns.add(resource.column());
      }
    }
    return new ResourceSelector(catalog, schema, table, columns);
  }
}