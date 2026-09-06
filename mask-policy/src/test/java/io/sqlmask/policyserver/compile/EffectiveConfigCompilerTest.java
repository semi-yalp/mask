package io.sqlmask.policyserver.compile;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        EffectiveConfigCompiler.compile(INSTANCE, policies);

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
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(INSTANCE, List.of());
    assertNull(response.config().metadata().tables().get(0).rowFilter());
    assertEquals(0, response.config().columns().size());
  }
}
