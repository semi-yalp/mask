package io.sqlmask.policyserver.compile;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveConfigCompilerTest {

  private static EngineInstance instance(TableDef... tables) {
    return new EngineInstance("pg", "postgresql", null, null, List.of(tables));
  }

  private static TableDef table(String name, ColumnDef... columns) {
    return new TableDef("crm", "public", name, List.of(columns));
  }

  private static ColumnDef col(String name) {
    return new ColumnDef(name, "varchar");
  }

  private static PolicyEntity mask(String name, ResourceSelector resource, int priority) {
    return new PolicyEntity(name, AccessType.SELECT, PolicyType.DATAMASK, true, priority, resource,
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0);
  }

  private static PolicyEntity maskForUser(String name, ResourceSelector resource, String user) {
    return new PolicyEntity(name, AccessType.SELECT, PolicyType.DATAMASK, true, 0, resource,
        new SubjectSelector(Set.of(user), Set.of()), "mask_phone", List.of(), null, 0);
  }

  private static PolicyEntity rowFilter(String name, ResourceSelector resource, String expr) {
    return new PolicyEntity(name, AccessType.SELECT, PolicyType.ROW_FILTER, true, 0, resource,
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), expr, 0);
  }

  @Test
  void globTableExpandsBindingsAcrossMatchingTables() {
    EngineInstance instance = instance(table("orders"), table("order_audit"), table("customers"));
    PolicyEntity glob = mask("p1", new ResourceSelector("crm", "public", "order*",
        List.of("phone")), 0);
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(glob), Subject.anonymous(), warnings);
    assertEquals(2, response.config().columns().size());
    assertTrue(response.config().columns().stream()
        .anyMatch(b -> b.table().equals("orders")));
    assertTrue(response.config().columns().stream()
        .anyMatch(b -> b.table().equals("order_audit")));
    assertFalse(response.config().columns().stream()
        .anyMatch(b -> b.table().equals("customers")));
    assertEquals(0, warnings.size());
  }

  @Test
  void globColumnExpandsToConcreteNames() {
    EngineInstance instance = instance(table("customer", col("phone"), col("phone_alt"),
        col("email")));
    PolicyEntity globColumn = mask("p1", new ResourceSelector("crm", "public", "customer",
        List.of("phone*")), 0);
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(globColumn), Subject.anonymous(), warnings);
    assertEquals(2, response.config().columns().size());
    assertTrue(response.config().columns().stream()
        .anyMatch(b -> b.column().equals("phone")));
    assertTrue(response.config().columns().stream()
        .anyMatch(b -> b.column().equals("phone_alt")));
    assertFalse(response.config().columns().stream()
        .anyMatch(b -> b.column().equals("email")));
  }

  @Test
  void effectiveConfigNeverContainsGlobLiterals() {
    EngineInstance instance = instance(table("customer", col("phone")), table("orders"));
    PolicyEntity glob = mask("p1", new ResourceSelector("crm", "public", "cust*",
        List.of("phone")), 0);
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(glob), Subject.anonymous(), warnings);
    for (EffectiveConfigResponse.ColumnBinding binding : response.config().columns()) {
      assertFalse(binding.table().contains("*") || binding.table().contains("?"));
      assertFalse(binding.column().contains("*") || binding.column().contains("?"));
    }
    for (EffectiveConfigResponse.TablePayload table : response.config().metadata().tables()) {
      assertFalse(table.name().contains("*") || table.name().contains("?"));
    }
  }

  @Test
  void globMatchingNoTableProducesWarningAndNoBinding() {
    EngineInstance instance = instance(table("customer"));
    PolicyEntity glob = mask("p1", new ResourceSelector("crm", "public", "nope*",
        List.of("phone")), 0);
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(glob), Subject.anonymous(), warnings);
    assertEquals(0, response.config().columns().size());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("matches no snapshot table")));
  }

  @Test
  void globColumnMatchingNothingProducesWarning() {
    EngineInstance instance = instance(table("customer", col("phone")));
    PolicyEntity glob = mask("p1", new ResourceSelector("crm", "public", "customer",
        List.of("zzz*")), 0);
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(glob), Subject.anonymous(), warnings);
    assertEquals(0, response.config().columns().size());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("matches no column")));
  }

  @Test
  void anonymousSubjectOnlyMatchesWildcardPolicies() {
    EngineInstance instance = instance(table("customer", col("phone")));
    PolicyEntity forAlice = maskForUser("p_alice", new ResourceSelector("crm", "public",
        "customer", List.of("phone")), "alice");
    PolicyEntity everyone = mask("p_all", new ResourceSelector("crm", "public", "customer",
        List.of("email")), 0);
    EffectiveConfigResponse anonymous = EffectiveConfigCompiler.compile(instance,
        List.of(forAlice, everyone), Subject.anonymous(), new ArrayList<>());
    // Only the wildcard policy applies to an anonymous subject; the user-scoped
    // policy is filtered out entirely.
    assertEquals(1, anonymous.policySummary().enabled());
    assertEquals(1, anonymous.config().columns().size());
    assertEquals("p_all", anonymous.config().columns().get(0).policy());
    assertEquals(0, anonymous.config().columns().stream()
        .filter(b -> b.policy().equals("p_alice")).count());
  }

  @Test
  void higherPriorityClaimsColumnOwnership() {
    EngineInstance instance = instance(table("customer", col("phone")));
    PolicyEntity low = mask("low", new ResourceSelector("crm", "public", "customer",
        List.of("phone")), 1);
    PolicyEntity high = mask("high", new ResourceSelector("crm", "public", "customer",
        List.of("phone")), 10);
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(low, high), Subject.anonymous(), new ArrayList<>());
    assertEquals(1, response.config().columns().size());
    assertEquals("high", response.config().columns().get(0).policy());
  }

  @Test
  void globTableRowFilterAppliedPerMatchedTable() {
    EngineInstance instance = instance(table("orders"), table("order_audit"));
    PolicyEntity rf = rowFilter("rf1", new ResourceSelector("crm", "public", "order*",
        List.of()), "amount > 0");
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(rf), Subject.anonymous(), warnings);
    assertEquals("amount > 0", tablePayload(response, "orders").rowFilter());
    assertEquals("amount > 0", tablePayload(response, "order_audit").rowFilter());
  }

  @Test
  void multipleRowFiltersComposeWithAndInDecisionOrder() {
    EngineInstance instance = instance(table("orders"));
    PolicyEntity a = rowFilter("a", new ResourceSelector("crm", "public", "orders",
        List.of()), "x > 1");
    PolicyEntity b = rowFilter("b", new ResourceSelector("crm", "public", "orders",
        List.of()), "y < 2");
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(instance,
        List.of(a, b), Subject.anonymous(), new ArrayList<>());
    // Same priority resolves by name ascending (a then b), the same decision
    // order the old compiler used.
    assertEquals("(x > 1) AND (y < 2)", tablePayload(response, "orders").rowFilter());
  }

  private static EffectiveConfigResponse.TablePayload tablePayload(EffectiveConfigResponse r,
      String name) {
    return r.config().metadata().tables().stream()
        .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }
}