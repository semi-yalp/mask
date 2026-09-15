package io.sqlmask.policy.store;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every loader guard not exercised by {@link PolicyYamlLoaderTest}'s happy paths. */
class PolicyYamlLoaderGuardsTest {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();

  private static PolicyException parseFails(String yaml) {
    return assertThrows(PolicyException.class,
        () -> new PolicyYamlLoader().parse(yaml, "p.yaml"), () -> yaml);
  }

  @Test
  void rootMustBeAMapping() {
    PolicyException e = parseFails("- a\n- b");
    assertTrue(e.getMessage().contains("root must be a mapping"), () -> e.getMessage());
  }

  @Test
  void policyEntryMustBeAMapping() {
    PolicyException e = parseFails("policies:\n  - just_a_string");
    assertTrue(e.getMessage().contains("policies[0] must be a mapping"), () -> e.getMessage());
  }

  @Test
  void duplicateYamlKeysAreRejected() {
    PolicyException e = parseFails("""
        policies: []
        policies: []
        """);
    assertTrue(e.getMessage().contains("invalid YAML"), () -> e.getMessage());
  }

  @Test
  void disabledPolicyParsesWithExplicitFalse() {
    List<Policy> policies = loader.parse("""
        policies:
          - name: disabled-mask
            enabled: false
            priority: 2
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """, "p.yaml");
    assertFalse(policies.get(0).enabled());
    assertEquals(2, policies.get(0).priority());
  }

  @Test
  void enabledMustBeABoolean() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            enabled: "yes"
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("enabled must be a boolean"), () -> e.getMessage());
  }

  @Test
  void priorityMustBeAnInteger() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            priority: high
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("priority must be a integer"), () -> e.getMessage());
  }

  @Test
  void resourcesMustBeANonEmptyList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: []
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("resources must be a non-empty list"), () -> e.getMessage());
  }

  @Test
  void resourcesMustBeAList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: {catalog: c}
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("resources must be a non-empty list"), () -> e.getMessage());
  }

  @Test
  void resourceEntryMustBeAMapping() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: ["str"]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("resources[0] must be a mapping"), () -> e.getMessage());
  }

  @Test
  void resourceRequiredFieldsMayNotBeBlank() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: " ", table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage()
        .contains("resources[0].schema: required non-blank string is missing"), () -> e.getMessage());
  }

  @Test
  void dataMaskResourceRequiresColumnLevel() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("must declare a column level"), () -> e.getMessage());
  }

  @Test
  void columnMayNotBeAnEmptyList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: []}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("must not be an empty list"), () -> e.getMessage());
  }

  @Test
  void columnListEntriesMustBeNonBlankStrings() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: [phone, " "]}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("column[1] must be a non-blank string"), () -> e.getMessage());
  }

  @Test
  void columnMustBeAStringOrList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: {bad: map}}]
            dataMaskItems: [{groups: ["*"], udf: f}]
        """);
    assertTrue(e.getMessage().contains("must be a string, a list of strings"), () -> e.getMessage());
  }

  @Test
  void dataMaskItemsMustBeANonEmptyList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: []
        """);
    assertTrue(e.getMessage().contains("dataMaskItems must be a non-empty list"),
        () -> e.getMessage());
  }

  @Test
  void dataMaskItemMustBeAMapping() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: ["str"]
        """);
    assertTrue(e.getMessage().contains("dataMaskItems[0] must be a mapping"), () -> e.getMessage());
  }

  @Test
  void udfIsRequiredOnDataMaskItem() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"]}]
        """);
    assertTrue(e.getMessage()
        .contains("dataMaskItems[0].udf: required non-blank string is missing"), () -> e.getMessage());
  }

  @Test
  void argumentsMustBeAList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f, arguments: 3}]
        """);
    assertTrue(e.getMessage().contains("arguments must be a list"), () -> e.getMessage());
  }

  @Test
  void argumentsMustBeScalars() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: ["*"], udf: f, arguments: [[1, 2]]}]
        """);
    assertTrue(e.getMessage().contains("arguments[0] must be a scalar"), () -> e.getMessage());
  }

  @Test
  void rowFilterItemsMustBeANonEmptyList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t}]
            rowFilterItems: []
        """);
    assertTrue(e.getMessage().contains("rowFilterItems must be a non-empty list"),
        () -> e.getMessage());
  }

  @Test
  void filterExprIsRequiredOnRowFilterItem() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t}]
            rowFilterItems: [{groups: ["*"]}]
        """);
    assertTrue(e.getMessage()
        .contains("rowFilterItems[0].filterExpr: required non-blank string is missing"),
        () -> e.getMessage());
  }

  @Test
  void usersMustBeAList() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{users: alice, udf: f}]
        """);
    assertTrue(e.getMessage().contains("users must be a list of strings"), () -> e.getMessage());
  }

  @Test
  void subjectEntriesMustBeNonBlankStrings() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{groups: [devs, " "], udf: f}]
        """);
    assertTrue(e.getMessage().contains("groups[1] must be a non-blank string"), () -> e.getMessage());
  }

  @Test
  void neitherItemsKeyIsRejected() {
    PolicyException e = parseFails("""
        policies:
          - name: a
            resources: [{catalog: c, schema: s, table: t, column: x}]
        """);
    assertTrue(e.getMessage().contains("exactly one of dataMaskItems / rowFilterItems"),
        () -> e.getMessage());
  }

  @Test
  void tableLevelDefaultPriorityAndEnabled() {
    List<Policy> policies = loader.parse("""
        policies:
          - name: minimal
            resources: [{catalog: c, schema: s, table: t, column: x}]
            dataMaskItems: [{users: ["*"], udf: f}]
        """, "p.yaml");
    Policy policy = policies.get(0);
    assertTrue(policy.enabled());
    assertEquals(0, policy.priority());
    assertEquals(PolicyType.DATA_MASK, policy.type());
  }
}
