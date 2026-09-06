package io.sqlmask.policy.store;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyYamlLoaderTest {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();

  @Test
  void parsesMaskAndRowFilterPolicies() {
    String yaml = """
        policies:
          - name: mask-phone
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
          - name: filter-archived
            resources:
              - catalog: crm
                schema: public
                table: orders
            rowFilterItems:
              - users: [alice]
                filterExpr: "status <> 'archived'"
        """;
    List<Policy> policies = loader.parse(yaml, "policies.yaml");
    assertEquals(2, policies.size());
    Policy mask = policies.get(0);
    assertEquals("mask-phone", mask.name());
    assertEquals(true, mask.enabled());
    assertEquals(5, mask.priority());
    assertEquals(PolicyType.DATA_MASK, mask.type());
    assertEquals(2, mask.resources().size()); // column 列表展开
    assertEquals("phone", mask.resources().get(0).column());
    assertEquals("email", mask.resources().get(1).column());
    assertEquals(1, mask.dataMaskItems().size());
    assertEquals(List.of(3, 4), mask.dataMaskItems().get(0).arguments());
    Policy filter = policies.get(1);
    assertEquals(PolicyType.ROW_FILTER, filter.type());
    assertEquals("status <> 'archived'", filter.rowFilterItems().get(0).filterExpr());
  }

  @Test
  void emptyPoliciesListIsLegal() {
    assertEquals(List.of(), loader.parse("policies: []", "policies.yaml"));
  }

  @Test
  void duplicatePolicyNameIsRejected() {
    PolicyException e = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """, "policies.yaml"));
    assertTrue(e.getMessage().contains("policies[1]: duplicate policy name 'a'"));
  }

  @Test
  void itemsAreExclusive() {
    PolicyException both = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
            rowFilterItems: [{groups: ["*"], filterExpr: "x = 1"}]
        """, "policies.yaml"));
    assertTrue(both.getMessage().contains("exactly one of dataMaskItems / rowFilterItems"));
  }

  @Test
  void emptySubjectSelectorIsRejectedWithPath() {
    PolicyException e = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{udf: f}]
        """, "policies.yaml"));
    assertTrue(e.getMessage().contains("dataMaskItems[0]: users/groups"));
  }

  @Test
  void rowFilterResourceMustNotDeclareColumn() {
    PolicyException e = assertThrows(PolicyException.class, () -> loader.parse("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            rowFilterItems: [{groups: ["*"], filterExpr: "x = 1"}]
        """, "policies.yaml"));
    assertTrue(e.getMessage().contains("table-level"));
  }

  @Test
  void invalidYamlIsWrappedWithPathPrefix() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> loader.parse("policies: [ {", "policies.yaml"));
    assertTrue(e.getMessage().startsWith("policies.yaml: invalid YAML"));
  }

  @Test
  void missingPoliciesKeyIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> loader.parse("foo: 1", "policies.yaml"));
    assertTrue(e.getMessage().contains("'policies' must be a list"));
  }
}
