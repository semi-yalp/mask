package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEntityTest {

  private static PolicyEntity build(AccessType accessType, Integer priority) {
    return new PolicyEntity("p", accessType, PolicyType.DATAMASK, true, priority,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null, 0);
  }

  @Test
  void nullAccessTypeDefaultsToSelect() {
    assertEquals(AccessType.SELECT, build(null, null).accessType());
  }

  @Test
  void explicitAccessTypeIsKept() {
    assertEquals(AccessType.SELECT, build(AccessType.SELECT, null).accessType());
  }

  @Test
  void nullPriorityNormalizesToZero() {
    assertEquals(0, build(AccessType.SELECT, null).priority());
  }

  @Test
  void explicitPriorityIsKept() {
    assertEquals(5, build(AccessType.SELECT, 5).priority());
  }

  @Test
  void nullSubjectsDefaultToEveryoneWildcard() {
    PolicyEntity noSubjects = new PolicyEntity("p", null, PolicyType.ROW_FILTER, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of()), null, null, null,
        "active = true", 0);
    assertEquals(new SubjectSelector(Set.of("*"), Set.of()), noSubjects.subjects());
  }

  @Test
  void nullArgumentsNormalizeToEmpty() {
    PolicyEntity noArgs = new PolicyEntity("p", null, PolicyType.DATAMASK, true, null,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", null, null, 0);
    assertEquals(List.of(), noArgs.arguments());
  }

  @Test
  void convenienceConstructorDefaultsVersionToZero() {
    PolicyEntity p = new PolicyEntity("p", null, PolicyType.DATAMASK, true, 3,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    assertEquals(0, p.currentVersion());
  }
}