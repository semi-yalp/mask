package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Constructor invariants of the policy model not covered by the happy-path tests. */
class PolicyModelGuardsTest {

  private static final SubjectSelector EVERYONE = new SubjectSelector(Set.of(), Set.of("*"));
  private static final PolicyResource COLUMN_RESOURCE =
      PolicyResource.column("crm", "public", "customer", "phone");

  @Test
  void blankNameIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new Policy(" ", true, 0, PolicyType.DATA_MASK,
            List.of(COLUMN_RESOURCE), List.of(new DataMaskItem(EVERYONE, "f", List.of())), List.of()));
    assertTrue(e.getMessage().contains("policy name must not be blank"), () -> e.getMessage());
  }

  @Test
  void missingTypeIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new Policy("p", true, 0, null,
            List.of(COLUMN_RESOURCE), List.of(new DataMaskItem(EVERYONE, "f", List.of())), List.of()));
    assertTrue(e.getMessage().contains("type is required"), () -> e.getMessage());
  }

  @Test
  void emptyResourcesAreRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new Policy("p", true, 0, PolicyType.DATA_MASK,
            List.of(), List.of(new DataMaskItem(EVERYONE, "f", List.of())), List.of()));
    assertTrue(e.getMessage().contains("at least one resource"), () -> e.getMessage());
  }

  @Test
  void dataMaskPolicyWithoutItemsIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new Policy("p", true, 0, PolicyType.DATA_MASK,
            List.of(COLUMN_RESOURCE), List.of(), List.of()));
    assertTrue(e.getMessage().contains("dataMaskItems only"), () -> e.getMessage());
  }

  @Test
  void rowFilterPolicyWithoutItemsIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new Policy("p", true, 0, PolicyType.ROW_FILTER,
            List.of(PolicyResource.table("crm", "public", "customer")), List.of(), List.of()));
    assertTrue(e.getMessage().contains("rowFilterItems only"), () -> e.getMessage());
  }

  @Test
  void dataMaskItemRequiresSelectorAndUdf() {
    PolicyException noSelector = assertThrows(PolicyException.class,
        () -> new DataMaskItem(null, "f", List.of()));
    assertTrue(noSelector.getMessage().contains("subject selector"), () -> noSelector.getMessage());
    PolicyException noUdf = assertThrows(PolicyException.class,
        () -> new DataMaskItem(EVERYONE, " ", List.of()));
    assertTrue(noUdf.getMessage().contains("udf identifier"), () -> noUdf.getMessage());
  }

  @Test
  void rowFilterItemRequiresSelector() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> new RowFilterItem(null, "a = 1"));
    assertTrue(e.getMessage().contains("subject selector"), () -> e.getMessage());
  }

  @Test
  void nullSubjectMatchesNothing() {
    assertEquals(0, EVERYONE.matchLevel(null));
  }
}
