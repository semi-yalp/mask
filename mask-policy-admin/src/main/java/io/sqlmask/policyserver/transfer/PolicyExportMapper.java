package io.sqlmask.policyserver.transfer;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policyserver.model.PolicyEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Export direction of the policies.yaml exchange: one management-plane
 * {@link PolicyEntity} (a single resource plus a single selector plus one
 * payload) becomes one engine-plane {@link Policy} — one column-level
 * resource per masked column, one table-level resource for row filters, a
 * single item carrying the entity's selector. The inverse of
 * {@link PolicyImportMapper}.
 */
public final class PolicyExportMapper {

  public Policy toPolicy(PolicyEntity entity) {
    List<PolicyResource> resources = new ArrayList<>();
    if (entity.policyType() == io.sqlmask.policyserver.model.PolicyType.DATAMASK) {
      for (String column : entity.resource().columns()) {
        resources.add(PolicyResource.column(entity.resource().catalog(), entity.resource().schema(),
            entity.resource().table(), column));
      }
      List<DataMaskItem> items = List.of(new DataMaskItem(
          entity.subjects(), entity.udf(), entity.arguments()));
      return new Policy(entity.name(), entity.enabled(), entity.priority(), PolicyType.DATA_MASK,
          resources, items, List.of());
    }
    resources.add(PolicyResource.table(entity.resource().catalog(), entity.resource().schema(),
        entity.resource().table()));
    List<RowFilterItem> items = List.of(new RowFilterItem(entity.subjects(), entity.filterExpr()));
    return new Policy(entity.name(), entity.enabled(), entity.priority(), PolicyType.ROW_FILTER,
        resources, List.of(), items);
  }
}