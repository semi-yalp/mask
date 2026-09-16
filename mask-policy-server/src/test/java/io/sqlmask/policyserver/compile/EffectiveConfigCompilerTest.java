package io.sqlmask.policyserver.compile;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveConfigCompilerTest {

  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer", List.of(
          new ColumnDef("phone", "varchar"), new ColumnDef("email", "varchar")))));

  @Test
  void expandsSelectorsBackfillsRowFiltersAndCountsDisabled() {
    List<PolicyEntity> policies = List.of(
        new PolicyEntity("phone_mask", PolicyType.DATAMASK, true,
            new ResourceSelector("crm", "public", "customer", List.of("phone", "email")),
            "mask_phone", List.of(3, 4), null),
        new PolicyEntity("disabled_mask", PolicyType.DATAMASK, false,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "x", List.of(), null),
        new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
            new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
            "status = 'active'"));
    EffectiveConfigResponse response =
        EffectiveConfigCompiler.compile(INSTANCE, policies, Subject.of("alice", List.of()));

    assertEquals(2, response.policySummary().enabled());
    assertEquals(1, response.policySummary().disabled());
    var table = response.config().metadata().tables().get(0);
    assertEquals("status = 'active'", table.rowFilter());
    assertEquals(List.of(
            new EffectiveConfigResponse.ColumnBinding("crm", "public", "customer", "phone", "phone_mask"),
            new EffectiveConfigResponse.ColumnBinding("crm", "public", "customer", "email", "phone_mask")),
        response.config().columns());
    assertEquals(new EffectiveConfigResponse.UdfDefinition("mask_phone", List.of(3, 4)),
        response.config().policies().get("phone_mask"));
    assertEquals("varchar", table.columns().get(0).type());
  }

  @Test
  void blankRowFilterAbsentWhenNoRowFilterPolicy() {
    EffectiveConfigResponse response =
        EffectiveConfigCompiler.compile(INSTANCE, List.of(), Subject.of("alice", List.of()));
    assertNull(response.config().metadata().tables().get(0).rowFilter());
    assertEquals(0, response.config().columns().size());
  }

  // ---- 按主体过滤 ----

  @Test
  void anonymousSubjectSeesOnlyWildcardPolicies() {
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(
        INSTANCE, List.of(wildcardMask(), aliceMask()), Subject.anonymous());
    assertTrue(response.config().columns().stream()
        .noneMatch(c -> "alice_mask".equals(c.policy())));
    assertTrue(response.config().columns().stream()
        .anyMatch(c -> "wildcard_mask".equals(c.policy())));
    assertEquals(1, response.policySummary().enabled());
    assertEquals(1, response.policySummary().disabled()); // 未命中计入 disabled
  }

  @Test
  void namedSubjectSeesOwnAndWildcardPolicies() {
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(
        INSTANCE, List.of(wildcardMask(), aliceMask()), Subject.of("alice", List.of()));
    assertEquals(2, response.policySummary().enabled());
  }

  @Test
  void groupMembershipSelectsPolicies() {
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(
        INSTANCE, List.of(analystsRowFilter()), Subject.of("bob", List.of("analysts")));
    // row_filter 只对该主体回填到表上
    assertTrue(response.config().metadata().tables().stream()
        .anyMatch(t -> t.rowFilter() != null && t.rowFilter().contains("status")));
  }

  private static PolicyEntity wildcardMask() {
    return new PolicyEntity("wildcard_mask", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null);
  }

  private static PolicyEntity aliceMask() {
    return new PolicyEntity("alice_mask", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("alice"), Set.of()), "mask_phone", List.of(), null);
  }

  private static PolicyEntity analystsRowFilter() {
    return new PolicyEntity("analysts_filter", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of(), Set.of("analysts")), null, List.of(), "status = 'active'");
  }
}
