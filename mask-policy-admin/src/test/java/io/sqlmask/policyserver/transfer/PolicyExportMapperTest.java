package io.sqlmask.policyserver.transfer;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.ResourceSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyExportMapperTest {

  private final PolicyExportMapper mapper = new PolicyExportMapper();

  @Test
  void exportsDatamaskEntityAsColumnLevelPolicy() {
    PolicyEntity entity = new PolicyEntity("mask-phone", io.sqlmask.policyserver.model.PolicyType.DATAMASK,
        true, 5, new ResourceSelector("crm", "public", "customer", List.of("phone", "email")),
        new SubjectSelector(Set.of(), Set.of("*")), "mask_phone", List.of(3, 4), null);

    Policy policy = mapper.toPolicy(entity);

    assertEquals("mask-phone", policy.name());
    assertEquals(true, policy.enabled());
    assertEquals(5, policy.priority());
    assertEquals(PolicyType.DATA_MASK, policy.type());
    assertEquals(List.of(PolicyResource.column("crm", "public", "customer", "phone"),
        PolicyResource.column("crm", "public", "customer", "email")), policy.resources());
    assertEquals(List.of(new DataMaskItem(
        new SubjectSelector(Set.of(), Set.of("*")), "mask_phone", List.of(3, 4))),
        policy.dataMaskItems());
    assertTrue(policy.rowFilterItems().isEmpty());
  }

  @Test
  void exportsRowFilterEntityAsTableLevelPolicy() {
    PolicyEntity entity = new PolicyEntity("filter-archived", io.sqlmask.policyserver.model.PolicyType.ROW_FILTER,
        false, 2, new ResourceSelector("crm", "public", "orders", List.of()),
        new SubjectSelector(Set.of("alice"), Set.of()), null, null, "status <> 'archived'");

    Policy policy = mapper.toPolicy(entity);

    assertEquals(PolicyType.ROW_FILTER, policy.type());
    assertEquals(List.of(PolicyResource.table("crm", "public", "orders")), policy.resources());
    assertEquals(List.of(new RowFilterItem(
        new SubjectSelector(Set.of("alice"), Set.of()), "status <> 'archived'")),
        policy.rowFilterItems());
    assertTrue(policy.dataMaskItems().isEmpty());
  }
}