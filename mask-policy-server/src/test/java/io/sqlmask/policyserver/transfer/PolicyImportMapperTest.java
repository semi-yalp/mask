package io.sqlmask.policyserver.transfer;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.store.PolicyYamlLoader;
import io.sqlmask.policy.store.PolicyYamlWriter;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyImportMapperTest {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();
  private final PolicyImportMapper mapper = new PolicyImportMapper();

  private List<PolicyEntity> importYaml(String yaml) {
    return mapper.toEntities(loader.parse(yaml, "policies.yaml"));
  }

  @Test
  void mapsSimpleMaskPolicyToSingleEntity() {
    List<PolicyEntity> entities = importYaml("""
        policies:
          - name: mask-phone
            enabled: true
            priority: 5
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone, email]
            dataMaskItems:
              - groups: ["*"]
                udf: mask_phone
                arguments: [3, 4]
        """);

    assertEquals(1, entities.size());
    PolicyEntity entity = entities.get(0);
    assertEquals("mask-phone", entity.name());
    assertEquals(PolicyType.DATAMASK, entity.policyType());
    assertEquals(true, entity.enabled());
    assertEquals(5, entity.priority());
    assertEquals("crm", entity.resource().catalog());
    assertEquals("public", entity.resource().schema());
    assertEquals("customer", entity.resource().table());
    assertEquals(List.of("phone", "email"), entity.resource().columns());
    assertEquals(List.of("*"), List.copyOf(entity.subjects().groups()));
    assertEquals("mask_phone", entity.udf());
    assertEquals(List.of(3, 4), entity.arguments());
    assertNull(entity.filterExpr());
  }

  @Test
  void mapsRowFilterPolicyToSingleEntityWithoutColumns() {
    List<PolicyEntity> entities = importYaml("""
        policies:
          - name: filter-archived
            enabled: false
            resources:
              - catalog: crm
                schema: public
                table: orders
            rowFilterItems:
              - users: ["*"]
                filterExpr: "status <> 'archived'"
        """);

    assertEquals(1, entities.size());
    PolicyEntity entity = entities.get(0);
    assertEquals(PolicyType.ROW_FILTER, entity.policyType());
    assertEquals(false, entity.enabled());
    assertEquals(List.of(), entity.resource().columns());
    assertEquals(List.of("*"), List.copyOf(entity.subjects().users()));
    assertNull(entity.udf());
    assertEquals("status <> 'archived'", entity.filterExpr());
  }

  @Test
  void deduplicatesRepeatedColumns() {
    List<PolicyEntity> entities = importYaml("""
        policies:
          - name: mask-dupe
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone, email, phone]
            dataMaskItems:
              - users: [alice]
                udf: mask_phone
        """);

    assertEquals(List.of("phone", "email"), entities.get(0).resource().columns());
  }

  @Test
  void rejectsPolicyWithMultipleItems() {
    PolicyException e = assertThrows(PolicyException.class, () -> importYaml("""
        policies:
          - name: mask-multi
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
            dataMaskItems:
              - users: [alice]
                udf: mask_phone
              - groups: ["*"]
                udf: mask_phone
        """));

    assertTrue(e.getMessage().contains("mask-multi"));
    assertTrue(e.getMessage().contains("one item per policy"));
  }

  @Test
  void rejectsPolicySpanningMultipleTables() {
    PolicyException e = assertThrows(PolicyException.class, () -> importYaml("""
        policies:
          - name: mask-span
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
              - catalog: crm
                schema: public
                table: orders
                column: [phone]
            dataMaskItems:
              - users: [alice]
                udf: mask_phone
        """));

    assertTrue(e.getMessage().contains("mask-span"));
    assertTrue(e.getMessage().contains("single table"));
  }

  @Test
  void rejectsRowFilterPolicySpanningMultipleTables() {
    PolicyException e = assertThrows(PolicyException.class, () -> importYaml("""
        policies:
          - name: filter-span
            resources:
              - catalog: crm
                schema: public
                table: orders
              - catalog: crm
                schema: public
                table: invoices
            rowFilterItems:
              - users: [alice]
                filterExpr: "status = 1"
        """));

    assertTrue(e.getMessage().contains("filter-span"));
    assertTrue(e.getMessage().contains("single table"));
  }

  @Test
  void importsInheritOnCopyBackIntoSelector() {
    // 两个资源节点:phone 带继承、email 不带——粒度必须逐列保留
    List<PolicyEntity> entities = importYaml("""
        policies:
          - name: mask-phone
            enabled: true
            priority: 5
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: phone
                inheritOnCopy: true
              - catalog: crm
                schema: public
                table: customer
                column: email
            dataMaskItems:
              - groups: ["*"]
                udf: mask_phone
                arguments: [3, 4]
        """);

    assertEquals(1, entities.size());
    assertEquals(List.of("phone", "email"), entities.get(0).resource().columns());
    assertEquals(List.of("phone"), entities.get(0).resource().inheritColumns());
  }

  @Test
  void exportImportRoundTripPreservesInheritColumns() {
    PolicyEntity original = new PolicyEntity("mask-phone", PolicyType.DATAMASK,
        true, 5, new ResourceSelector("crm", "public", "customer",
            List.of("phone", "email"), List.of("phone")),
        new io.sqlmask.policy.model.SubjectSelector(java.util.Set.of(), java.util.Set.of("*")),
        "mask_phone", List.of(3, 4), null);

    String yaml = new PolicyYamlWriter().write(List.of(
        new io.sqlmask.policyserver.transfer.PolicyExportMapper().toPolicy(original)));
    PolicyEntity reimported = importYaml(yaml).get(0);

    assertEquals(List.of("phone", "email"), reimported.resource().columns());
    assertEquals(List.of("phone"), reimported.resource().inheritColumns());
  }
}