package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyModelTest {

  private static final SubjectSelector EVERYONE =
      new SubjectSelector(Set.of(), Set.of("*"));

  @Test
  void subjectOfNormalizesUserAndGroups() {
    Subject subject = Subject.of(" Alice ", Arrays.asList("  devs", "devs", "", null));
    assertEquals("Alice", subject.user());
    assertEquals(List.of("devs"), subject.groups());
  }

  @Test
  void anonymousSubjectMatchesOnlyWildcard() {
    Subject anonymous = Subject.anonymous();
    assertEquals(1, new SubjectSelector(Set.of(), Set.of("*")).matchLevel(anonymous));
    assertEquals(0, new SubjectSelector(Set.of("alice"), Set.of()).matchLevel(anonymous));
    assertEquals(0, new SubjectSelector(Set.of(), Set.of("devs")).matchLevel(anonymous));
  }

  @Test
  void selectorSpecificityUserBeatsGroupBeatsWildcard() {
    Subject alice = Subject.of("alice", List.of("devs"));
    assertEquals(3, new SubjectSelector(Set.of("alice", "bob"), Set.of("devs")).matchLevel(alice));
    assertEquals(2, new SubjectSelector(Set.of(), Set.of("devs", "ops")).matchLevel(alice));
    assertEquals(1, new SubjectSelector(Set.of(), Set.of("*")).matchLevel(alice));
    assertEquals(0, new SubjectSelector(Set.of(), Set.of("ops")).matchLevel(alice));
  }

  @Test
  void emptySelectorIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new SubjectSelector(Set.of(), Set.of()));
    assertTrue(e.getMessage().contains("non-empty users or groups"));
  }

  @Test
  void resourceLevelsAreNormalizedAndColumnMayBeNull() {
    PolicyResource column = PolicyResource.column("CRM", "Public", "Customer", "Phone");
    assertEquals("crm", column.catalog());
    assertEquals("public", column.schema());
    assertEquals("customer", column.table());
    assertEquals("phone", column.column());
    assertEquals(null, PolicyResource.table("crm", "public", "customer").column());
    assertThrows(PolicyException.class, () -> new PolicyResource("crm", "", "t", null));
  }

  @Test
  void dataMaskArgumentsMustBeScalars() {
    assertThrows(PolicyException.class,
        () -> new DataMaskItem(EVERYONE, "mask_phone", List.of(3, Map.of("a", 1))));
    assertThrows(PolicyException.class,
        () -> new DataMaskItem(EVERYONE, "mask_phone", Arrays.asList((Object) null)));
  }

  @Test
  void blankFilterExprIsRejected() {
    assertThrows(PolicyException.class, () -> new RowFilterItem(EVERYONE, "   "));
  }

  @Test
  void dataMaskPolicyRequiresColumnLevelAndOnlyDataMaskItems() {
    PolicyException noColumn = assertThrows(PolicyException.class,
        () -> new Policy("m", true, 0, PolicyType.DATA_MASK,
            List.of(PolicyResource.table("crm", "public", "customer")),
            List.of(new DataMaskItem(EVERYONE, "mask_phone", List.of())), List.of()));
    assertTrue(noColumn.getMessage().contains("column level"));

    PolicyException mixed = assertThrows(PolicyException.class,
        () -> new Policy("m", true, 0, PolicyType.DATA_MASK,
            List.of(PolicyResource.column("crm", "public", "customer", "phone")),
            List.of(new DataMaskItem(EVERYONE, "mask_phone", List.of())),
            List.of(new RowFilterItem(EVERYONE, "status = 'a'"))));
    assertTrue(mixed.getMessage().contains("dataMaskItems only"));
  }

  @Test
  void rowFilterPolicyIsTableLevelAndOnlyRowFilterItems() {
    PolicyException withColumn = assertThrows(PolicyException.class,
        () -> new Policy("f", true, 0, PolicyType.ROW_FILTER,
            List.of(PolicyResource.column("crm", "public", "orders", "status")),
            List.of(),
            List.of(new RowFilterItem(EVERYONE, "status <> 'archived'"))));
    assertTrue(withColumn.getMessage().contains("table-level"));
  }

  @Test
  void resourceRetainsFourArgConstructorDefaultingInheritOff() {
    PolicyResource r = new PolicyResource("crm", "public", "customer", "phone");
    assertFalse(r.inheritOnCopy());
    PolicyResource r2 = new PolicyResource("crm", "public", "customer", "phone", true);
    assertTrue(r2.inheritOnCopy());
    // 表级(列空)资源带 inheritOnCopy 由 Policy 构造器负责拒绝,resource 本身不拦
  }

  @Test
  void inheritOnCopyRejectedOnRowFilterResource() {
    PolicyResource tableResource =
        new PolicyResource("crm", "public", "customer", null, true);
    PolicyException e = assertThrows(PolicyException.class, () -> new Policy(
        "rf", true, 0, PolicyType.ROW_FILTER,
        List.of(tableResource), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")), "id > 0"))));
    assertTrue(e.getMessage().contains("inheritOnCopy is only allowed on dataMask"));
  }

  @Test
  void inheritOnCopyAllowedOnDataMaskResource() {
    PolicyResource colResource =
        new PolicyResource("crm", "public", "customer", "phone", true);
    Policy p = new Policy("dm", true, 0, PolicyType.DATA_MASK,
        List.of(colResource),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")), "mask_phone",
            List.of())),
        List.of());
    assertTrue(p.resources().get(0).inheritOnCopy());
  }
}
