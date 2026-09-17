package io.sqlmask.policyserver.model;

import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEntityTest {

  private static PolicyEntity canonical(Integer priority) {
    return new PolicyEntity("p", PolicyType.DATAMASK, true, priority,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void nullPriorityNormalizesToZero() {
    assertEquals(0, canonical(null).priority());
  }

  @Test
  void explicitPriorityIsKept() {
    assertEquals(5, canonical(5).priority());
  }

  @Test
  void legacyArityConstructorsDefaultToZero() {
    assertEquals(0, new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4),
        null).priority());
    assertEquals(0, new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null).priority());
  }
}
