package io.sqlmask.rewrite;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.lineage.LineageStatus;
import io.sqlmask.lineage.OutputLineage;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.MaskingPolicy;
import io.sqlmask.policy.PolicyRegistry;
import io.sqlmask.policy.PolicySelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewritePlanTest {

  private final PolicySelector selector = selectorForPhoneAndEmail();

  private static PolicySelector selectorForPhoneAndEmail() {
    MaskingPolicy phoneMask = new MaskingPolicy("phone_mask", "mask_phone", List.of(3, 4));
    MaskingPolicy emailMask = new MaskingPolicy("email_mask", "mask_email", List.of());
    Map<ColumnKey, MaskingPolicy> bindings = Map.of(
        ColumnKey.of("crm", "public", "customer", "phone"), phoneMask,
        ColumnKey.of("crm", "public", "customer", "email"), emailMask);
    return new PolicySelector(new PolicyRegistry(
        Map.of("phone_mask", phoneMask, "email_mask", emailMask), bindings));
  }

  private static OutputLineage lineage(int ordinal, String name, LineageStatus status,
      Set<ColumnOrigin> origins) {
    return new OutputLineage(ordinal, name, null, origins, status);
  }

  private static ColumnOrigin origin(String column) {
    return ColumnOrigin.of(ColumnKey.of("crm", "public", "customer", column),
        "crm.public.customer", false);
  }

  @Test
  void allUnmaskedOutputsProduceNoWrapper() {
    RewritePlan plan = RewritePlan.of(List.of(
        lineage(0, "id", LineageStatus.RESOLVED, Set.of(origin("id"))),
        lineage(1, "constant", LineageStatus.NO_ORIGIN, Set.of())), selector);
    assertFalse(plan.requiresWrapper());
    assertEquals(List.of("id", "constant"),
        plan.outputs().stream().map(OutputRewrite::outputName).toList());
    assertTrue(plan.outputs().stream().noneMatch(OutputRewrite::isMasked));
  }

  @Test
  void partialMaskingMarksWrapperAndKeepsPassthrough() {
    RewritePlan plan = RewritePlan.of(List.of(
        lineage(0, "id", LineageStatus.RESOLVED, Set.of(origin("id"))),
        lineage(1, "phone", LineageStatus.RESOLVED, Set.of(origin("phone")))), selector);
    assertTrue(plan.requiresWrapper());
    assertFalse(plan.outputs().get(0).isMasked());
    assertTrue(plan.outputs().get(1).isMasked());
    assertEquals("phone_mask", plan.outputs().get(1).policy().get().name());
    assertEquals(3, plan.outputs().get(1).policy().get().arguments().get(0));
  }

  @Test
  void constantOutputsPassThrough() {
    RewritePlan plan = RewritePlan.of(List.of(
        lineage(0, "constant_value", LineageStatus.NO_ORIGIN, Set.of())), selector);
    assertFalse(plan.requiresWrapper());
    assertFalse(plan.outputs().get(0).isMasked());
  }

  @Test
  void unknownLineageFails() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> RewritePlan.of(List.of(
        lineage(0, "p", LineageStatus.UNKNOWN, Set.of())), selector));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
    assertTrue(e.getMessage().contains("'p'"), () -> e.getMessage());
  }

  @Test
  void outputOrderIsPreservedByOrdinal() {
    RewritePlan plan = RewritePlan.of(List.of(
        lineage(2, "email", LineageStatus.RESOLVED, Set.of(origin("email"))),
        lineage(0, "id", LineageStatus.RESOLVED, Set.of(origin("id"))),
        lineage(1, "phone", LineageStatus.RESOLVED, Set.of(origin("phone")))), selector);
    assertEquals(List.of(2, 0, 1),
        plan.outputs().stream().map(OutputRewrite::ordinal).toList());
  }
}
