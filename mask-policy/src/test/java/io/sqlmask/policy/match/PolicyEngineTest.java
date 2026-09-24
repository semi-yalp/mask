package io.sqlmask.policy.match;

import io.sqlmask.policy.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @Test
  void maskMatchesGlobPatternAtEveryLevel() {
    Policy p = new Policy("glob", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("c*", "pub*", "orders_*", "phone_*")),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_glob", List.of())),
        List.of());
    MaskInstruction instruction = new PolicyEngine(PolicyIndex.of(List.of(p)))
        .maskFor("crm", "public", "orders_2026", "phone_last4", ANON)
        .orElseThrow();
    assertEquals("mask_glob", instruction.udf());
  }

  @Test
  void globPatternDoesNotMatchUnrelatedName() {
    Policy p = new Policy("glob", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "order_*", "phone")),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_glob", List.of())),
        List.of());
    assertTrue(new PolicyEngine(PolicyIndex.of(List.of(p)))
        .maskFor("crm", "public", "refund_2026", "phone", ANON).isEmpty());
    assertTrue(new PolicyEngine(PolicyIndex.of(List.of(p)))
        .maskFor("crm", "public", "orders_2026", "email", ANON).isEmpty());
  }

  @Test
  void samePriorityGlobAndExactResolveInDeclarationOrder() {
    Policy broad = new Policy("broad", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "orders_*", "phone")),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_broad", List.of())),
        List.of());
    Policy exact = new Policy("exact", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "orders_2026", "phone")),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_exact", List.of())),
        List.of());
    assertEquals("mask_broad", new PolicyEngine(PolicyIndex.of(List.of(broad, exact)))
        .maskFor("crm", "public", "orders_2026", "phone", ANON).orElseThrow().udf());
  }

  @Test
  void rowFilterMatchesGlobTablePattern() {
    Policy p = new Policy("rf", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "tmp_*")),
        List.of(), List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")), "tenant = 1")));
    List<RowFilterHit> hits = new PolicyEngine(PolicyIndex.of(List.of(p)))
        .rowFiltersFor("crm", "public", "tmp_orders", ANON);
    assertEquals(1, hits.size());
    assertEquals("tenant = 1", hits.get(0).expr());
  }

  @Test
  void maskInheritsOnCopyMatchesDeclaredColumnOnly() {
    Policy inherited = new Policy("crm.phone", true, 0, PolicyType.DATA_MASK,
        List.of(new PolicyResource("crm", "public", "customer", "phone", true)),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_phone", List.of())),
        List.of());
    Policy plain = new Policy("crm.name", true, 0, PolicyType.DATA_MASK,
        List.of(new PolicyResource("crm", "public", "customer", "name", false)),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_name", List.of())),
        List.of());
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(inherited, plain)));
    assertTrue(engine.maskInheritsOnCopy("crm", "public", "customer", "phone"));
    assertFalse(engine.maskInheritsOnCopy("crm", "public", "customer", "name"));
    assertFalse(engine.maskInheritsOnCopy("crm", "public", "other", "phone"));
  }

  @Test
  void maskItemsForReturnsAllMatchingItemsInDecisionOrder() {
    DataMaskItem item = new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_phone", List.of());
    Policy inherited = new Policy("crm.phone", true, 0, PolicyType.DATA_MASK,
        List.of(new PolicyResource("crm", "public", "customer", "phone", true)),
        List.of(item), List.of());
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(inherited)));
    List<DataMaskItem> items = engine.maskItemsFor("crm", "public", "customer", "phone");
    assertEquals(1, items.size());
    assertEquals("mask_phone", items.get(0).udf());
    assertTrue(engine.maskItemsFor("crm", "public", "customer", "name").isEmpty());
  }

  @Test
  void maskItemsForMergesAllMatchingPoliciesInPriorityOrder() {
    Policy high = new Policy("a", true, 10, PolicyType.DATA_MASK,
        List.of(new PolicyResource("crm", "public", "customer", "phone", false)),
        List.of(new DataMaskItem(new SubjectSelector(Set.of("alice"), Set.of()), "mask_a",
            List.of())),
        List.of());
    Policy low = new Policy("b", true, 0, PolicyType.DATA_MASK,
        List.of(new PolicyResource("crm", "public", "customer", "phone", false)),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_b",
            List.of())),
        List.of());
    // 声明顺序 low 在前,断言顺序仍由 priority 决定:高优先级(10)的 mask_a 在前。
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(low, high)));
    List<DataMaskItem> items = engine.maskItemsFor("crm", "public", "customer", "phone");
    assertEquals(2, items.size());
    assertEquals("mask_a", items.get(0).udf());
    assertEquals("mask_b", items.get(1).udf());
    // 每个 item 的 selector/udf 原样保留。
    assertEquals(new SubjectSelector(Set.of("alice"), Set.of()), items.get(0).selector());
    assertEquals(new SubjectSelector(Set.of(), Set.of("*")), items.get(1).selector());
    assertEquals(List.of(), items.get(0).arguments());
    assertEquals(List.of(), items.get(1).arguments());
  }
}
