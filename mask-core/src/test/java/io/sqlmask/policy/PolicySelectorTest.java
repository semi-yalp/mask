package io.sqlmask.policy;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.metadata.ColumnKey;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicySelectorTest {

  private static final String TABLE = "crm.public.customer";

  private PolicySelector selectorFor(String... boundColumns) {
    java.util.Map<String, MaskingPolicy> policies = new java.util.LinkedHashMap<>();
    java.util.Map<ColumnKey, MaskingPolicy> bindings = new java.util.LinkedHashMap<>();
    for (String column : boundColumns) {
      MaskingPolicy policy = new MaskingPolicy(column + "_mask", "mask_" + column, List.of());
      policies.put(column + "_mask", policy);
      bindings.put(ColumnKey.of("crm", "public", "customer", column), policy);
    }
    return new PolicySelector(new PolicyRegistry(policies, bindings));
  }

  private static ColumnOrigin origin(String column) {
    return ColumnOrigin.of(ColumnKey.of("crm", "public", "customer", column), TABLE, false);
  }

  @Test
  void noOriginPolicyProducesNoSelection() {
    PolicySelector selector = selectorFor("phone");
    Optional<MaskingPolicy> selected = selector.select(
        Set.of(origin("id"), origin("status")));
    assertTrue(selected.isEmpty());
  }

  @Test
  void emptyOriginSetProducesNoSelection() {
    PolicySelector selector = selectorFor("phone");
    assertTrue(selector.select(Set.of()).isEmpty());
  }

  @Test
  void oneMatchingOriginSelectsItsPolicy() {
    PolicySelector selector = selectorFor("phone");
    Optional<MaskingPolicy> selected = selector.select(Set.of(origin("id"), origin("phone")));
    assertTrue(selected.isPresent());
    assertEquals("mask_phone", selected.get().udf());
  }

  @Test
  void multipleMatchesUseNormalizedColumnKeyOrder() {
    // 'email' < 'phone' lexicographically; the selection must not depend on
    // iteration order of the origin set or on YAML declaration order
    PolicySelector selector = selectorFor("phone", "email");
    Set<ColumnOrigin> origins = new HashSet<>(Set.of(origin("email"), origin("phone")));
    for (int i = 0; i < 20; i++) {
      Set<ColumnOrigin> shuffled = new HashSet<>();
      shuffled.addAll(origins);
      assertEquals(Optional.of("email_mask"),
          selector.select(shuffled).map(MaskingPolicy::name),
          "selection must be deterministic");
    }
  }
}
