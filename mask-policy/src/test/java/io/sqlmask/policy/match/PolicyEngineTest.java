package io.sqlmask.policy.match;

import io.sqlmask.policy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {

  private static final Subject ANON = Subject.anonymous();
  private static final Subject ALICE = Subject.of("alice", List.of("devs"));

  private static Policy maskPolicy(String name, int priority, boolean enabled, String column,
      SubjectSelector selector, String udf) {
    return new Policy(name, enabled, priority, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", column)),
        List.of(new DataMaskItem(selector, udf, List.of())), List.of());
  }

  private static Policy filterPolicy(String name, int priority, SubjectSelector selector,
      String expr) {
    return new Policy(name, true, priority, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "orders")),
        List.of(), List.of(new RowFilterItem(selector, expr)));
  }

  @Test
  void maskMatchesWildcardLevelsAndReturnsInstruction() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(maskPolicy("m", 0, true,
        "*", new SubjectSelector(Set.of(), Set.of("*")), "mask_all"))));
    MaskInstruction instruction = engine.maskFor("CRM", "Public", "Customer", "phone", ALICE)
        .orElseThrow();
    assertEquals("m", instruction.policyName());
    assertEquals("mask_all", instruction.udf());
  }

  @Test
  void higherPriorityWinsAndTiesGoToDeclarationOrder() {
    Policy low = maskPolicy("low", 0, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_low");
    Policy high = maskPolicy("high", 1, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_high");
    assertEquals("high", new PolicyEngine(PolicyIndex.of(List.of(low, high)))
        .maskFor("crm", "public", "customer", "phone", ANON).orElseThrow().policyName());
    // 同 priority：声明顺序
    Policy first = maskPolicy("first", 0, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_first");
    Policy second = maskPolicy("second", 0, true, "phone",
        new SubjectSelector(Set.of(), Set.of("*")), "mask_second");
    assertEquals("first", new PolicyEngine(PolicyIndex.of(List.of(first, second)))
        .maskFor("crm", "public", "customer", "phone", ANON).orElseThrow().policyName());
  }

  @Test
  void disabledPoliciesAreSkipped() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(
        maskPolicy("off", 9, false, "phone", new SubjectSelector(Set.of(), Set.of("*")), "x"),
        maskPolicy("on", 0, true, "phone", new SubjectSelector(Set.of(), Set.of("*")), "y"))));
    assertEquals("on", engine.maskFor("crm", "public", "customer", "phone", ANON)
        .orElseThrow().policyName());
  }

  @Test
  void itemSpecificityUserBeatsGroupBeatsWildcard() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(maskPolicy("m", 0, true,
        "phone",
        new SubjectSelector(Set.of("alice"), Set.of("devs", "ops")), "mask_user"))));
    assertEquals("mask_user", engine.maskFor("crm", "public", "customer", "phone", ALICE)
        .orElseThrow().udf());
  }

  @Test
  void noMatchYieldsEmptyMaskAndEmptyFilters() {
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(maskPolicy("m", 0, true,
        "phone", new SubjectSelector(Set.of("bob"), Set.of()), "mask_bob"))));
    assertTrue(engine.maskFor("crm", "public", "customer", "phone", ANON).isEmpty());
    assertTrue(engine.rowFiltersFor("crm", "public", "orders", ANON).isEmpty());
  }

  @Test
  void rowFiltersAccumulateAllMatchingPoliciesInDecisionOrder() {
    Policy p1 = filterPolicy("f-low", 0, new SubjectSelector(Set.of(), Set.of("*")), "a = 1");
    Policy p2 = filterPolicy("f-high", 5, new SubjectSelector(Set.of("alice"), Set.of()), "b = 2");
    List<RowFilterHit> hits = new PolicyEngine(PolicyIndex.of(List.of(p1, p2)))
        .rowFiltersFor("crm", "public", "orders", ALICE);
    assertEquals(2, hits.size());
    assertEquals("f-high", hits.get(0).policyName()); // priority 高者在前
    assertEquals("f-low", hits.get(1).policyName());
  }

  @Test
  void rowFilterSkippedForSubjectWithoutMatch() {
    Policy p = filterPolicy("f", 0, new SubjectSelector(Set.of("alice"), Set.of()), "a = 1");
    assertTrue(new PolicyEngine(PolicyIndex.of(List.of(p)))
        .rowFiltersFor("crm", "public", "orders", ANON).isEmpty());
  }
}
