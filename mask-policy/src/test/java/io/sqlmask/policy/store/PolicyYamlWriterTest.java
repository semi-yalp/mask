package io.sqlmask.policy.store;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyYamlWriterTest {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();
  private final PolicyYamlWriter writer = new PolicyYamlWriter();

  @Test
  void roundTripsMaskAndRowFilterPolicies() {
    List<Policy> policies = List.of(
        new Policy("mask-phone", true, 5, PolicyType.DATA_MASK,
            List.of(PolicyResource.column("crm", "public", "customer", "phone"),
                PolicyResource.column("crm", "public", "customer", "email")),
            List.of(new DataMaskItem(
                new SubjectSelector(Set.of(), Set.of("*")), "mask_phone", List.of(3, 4))),
            List.of()),
        new Policy("filter-archived", true, 0, PolicyType.ROW_FILTER,
            List.of(PolicyResource.table("crm", "public", "orders")),
            List.of(),
            List.of(new RowFilterItem(
                new SubjectSelector(Set.of("alice"), Set.of()), "status <> 'archived'"))));

    List<Policy> reloaded = loader.parse(writer.write(policies), "policies.yaml");

    assertEquals(policies, reloaded);
  }

  @Test
  void quotesScalarsThatWouldParseAsSomethingElse() {
    // "007" would parse as octal 7, "true" as boolean, "*" as an alias,
    // and filterExpr's '# ' would start a comment — the writer must quote all.
    List<Policy> policies = List.of(
        new Policy("mask-tricky", true, 0, PolicyType.DATA_MASK,
            List.of(PolicyResource.column("crm", "public", "customer", "phone")),
            List.of(new DataMaskItem(
                new SubjectSelector(Set.of("*"), Set.of()),
                "mask_phone", List.of("007", "true", "*"))),
            List.of()),
        new Policy("filter-tricky", true, 0, PolicyType.ROW_FILTER,
            List.of(PolicyResource.table("crm", "public", "orders")),
            List.of(),
            List.of(new RowFilterItem(
                new SubjectSelector(Set.of("bob"), Set.of()),
                "status = 'a:b' # note"))));

    List<Policy> reloaded = loader.parse(writer.write(policies), "policies.yaml");

    assertEquals(policies, reloaded);
  }

  @Test
  void emptyPolicyListWritesAnEmptyList() {
    assertEquals(List.of(), loader.parse(writer.write(List.of()), "policies.yaml"));
  }
}