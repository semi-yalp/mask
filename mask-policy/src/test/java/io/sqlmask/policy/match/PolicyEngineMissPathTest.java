package io.sqlmask.policy.match;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-matching policies must be skipped in both decisions: a policy scoped to
 * another resource must neither mask nor filter, and must not stop later
 * policies from matching.
 */
class PolicyEngineMissPathTest {

  private static final Subject ANON = Subject.anonymous();
  private static final SubjectSelector EVERYONE = new SubjectSelector(Set.of(), Set.of("*"));

  @Test
  void maskSkipsPoliciesScopedToOtherResources() {
    Policy otherTable = new Policy("other-table", true, 9, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "orders", "note")),
        List.of(new DataMaskItem(EVERYONE, "mask_wrong", List.of())), List.of());
    Policy otherColumn = new Policy("other-column", true, 8, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "email")),
        List.of(new DataMaskItem(EVERYONE, "mask_wrong", List.of())), List.of());
    Policy otherSchema = new Policy("other-schema", true, 7, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "archive", "customer", "phone")),
        List.of(new DataMaskItem(EVERYONE, "mask_wrong", List.of())), List.of());
    Policy hit = new Policy("hit", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "phone")),
        List.of(new DataMaskItem(EVERYONE, "mask_right", List.of())), List.of());
    PolicyEngine engine = new PolicyEngine(
        PolicyIndex.of(List.of(otherTable, otherColumn, otherSchema, hit)));
    assertEquals("hit", engine.maskFor("crm", "public", "customer", "phone", ANON)
        .orElseThrow().policyName());
  }

  @Test
  void rowFilterSkipsPoliciesScopedToOtherTables() {
    Policy other = new Policy("other", true, 9, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "customer")),
        List.of(), List.of(new RowFilterItem(EVERYONE, "wrong = 1")));
    Policy hit = new Policy("hit", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table("crm", "public", "orders")),
        List.of(), List.of(new RowFilterItem(EVERYONE, "right = 1")));
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(other, hit)));
    List<?> hits = engine.rowFiltersFor("crm", "public", "orders", ANON);
    assertEquals(1, hits.size());
    assertEquals("hit", ((io.sqlmask.policy.model.RowFilterHit) hits.get(0)).policyName());
  }

  @Test
  void maskMissOnEveryLevelYieldsEmpty() {
    Policy policy = new Policy("p", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "phone")),
        List.of(new DataMaskItem(EVERYONE, "mask_phone", List.of())), List.of());
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(List.of(policy)));
    assertTrue(engine.maskFor("other", "public", "customer", "phone", ANON).isEmpty());
    assertTrue(engine.maskFor("crm", "other", "customer", "phone", ANON).isEmpty());
    assertTrue(engine.maskFor("crm", "public", "other", "phone", ANON).isEmpty());
    assertTrue(engine.maskFor("crm", "public", "customer", "other", ANON).isEmpty());
  }
}
