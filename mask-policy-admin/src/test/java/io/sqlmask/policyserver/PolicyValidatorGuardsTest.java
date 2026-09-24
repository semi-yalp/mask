package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validation guards not exercised by {@link PolicyValidatorTest}'s matrix. */
class PolicyValidatorGuardsTest {

  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer", List.of(
          new ColumnDef("phone", "varchar"), new ColumnDef("status", "varchar")))));

  private static final List<UdfDefinition> UDFS = List.of(
      new UdfDefinition("mask_phone", List.of(
          new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"))));

  private final PolicyValidator validator = new PolicyValidator();

  private PolicyEntity datamask(String name, List<String> columns, String udf) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", columns), udf, List.of(3, 4), null);
  }

  @Test
  void duplicateTableInInstanceIsRejected() {
    EngineInstance duplicated = new EngineInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer",
            List.of(new ColumnDef("phone", "varchar"))),
        new TableDef("crm", "public", "customer",
            List.of(new ColumnDef("id", "bigint")))));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validateInstance(duplicated));
    assertTrue(e.getMessage().contains("duplicate table 'crm.public.customer'"),
        () -> e.getMessage());
  }

  @Test
  void duplicateColumnInTableIsRejected() {
    EngineInstance duplicated = new EngineInstance("pg_prod", "postgresql",
        List.of(new TableDef("crm", "public", "customer", List.of(
            new ColumnDef("phone", "varchar"), new ColumnDef("PHONE", "varchar")))));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validateInstance(duplicated));
    assertTrue(e.getMessage().contains("duplicate column 'PHONE'"), () -> e.getMessage());
  }

  @Test
  void blankUdfOnDatamaskIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, datamask("p", List.of("phone"), " "), List.of()));
    assertTrue(e.getMessage().contains("datamask requires a udf"), () -> e.getMessage());
  }

  @Test
  void emptyColumnListOnDatamaskIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, datamask("p", List.of(), "mask_phone"), List.of()));
    assertTrue(e.getMessage().contains("at least one column"), () -> e.getMessage());
  }

  @Test
  void blankFilterExprOnRowFilterIsRejected() {
    PolicyEntity filter = new PolicyEntity("p", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(), " ");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, filter, List.of()));
    assertTrue(e.getMessage().contains("row_filter requires filterExpr"), () -> e.getMessage());
  }

  @Test
  void policyOnAnotherTableDoesNotOverlap() {
    PolicyEntity onCustomer = datamask("on_customer", List.of("phone"), "mask_phone");
    PolicyEntity onOrders = new PolicyEntity("on_orders", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "orders", List.of("id")),
        "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, onCustomer, List.of(onOrders)));
  }

  @Test
  void danglingPolicyColumnIsNamedByUdfResolutionCheck() {
    // instance tables drifted: the stored policy references a dropped column
    List<String> failing = validator.policiesFailingUdfResolution(INSTANCE, UDFS,
        List.of(datamask("stale", List.of("fax"), "mask_phone")));
    assertEquals(List.of("stale"), failing);
  }
}
