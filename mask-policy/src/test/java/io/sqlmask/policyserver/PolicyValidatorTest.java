package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyValidatorTest {

  // `status`/`id` are declared because the row-filter cases below use them:
  // "status = 'active'" must be an ACCEPTED baseline filter and "id > 0" must
  // fail on the overlap rule, not on an unknown column (the RowFilterRegistry
  // whitelist rejects filters referencing undeclared columns).
  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer", List.of(
          new ColumnDef("phone", "varchar"), new ColumnDef("email", "varchar"),
          new ColumnDef("status", "varchar"), new ColumnDef("id", "varchar")))));

  private final PolicyValidator validator = new PolicyValidator();

  private PolicyEntity datamask(String name, String table, List<String> columns) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", table, columns), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void acceptsValidDatamaskPolicy() {
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, datamask("phone_mask", "customer", List.of("phone")), List.of()));
  }

  @Test
  void rejectsUnknownTableAndColumn() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(INSTANCE, datamask("p", "no_such", List.of("phone")), List.of()))
        .getMessage().contains("no_such"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(INSTANCE, datamask("p", "customer", List.of("fax")), List.of()))
        .getMessage().contains("fax"));
  }

  @Test
  void rejectsOverlapWithEnabledPolicy() {
    PolicyEntity existing = datamask("a_mask", "customer", List.of("phone", "email"));
    PolicyEntity overlapping = datamask("b_mask", "customer", List.of("email"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, overlapping, List.of(existing)));
    assertTrue(e.getMessage().contains("a_mask") && e.getMessage().contains("b_mask"));
  }

  @Test
  void disabledPoliciesDoNotBlock() {
    PolicyEntity disabled = new PolicyEntity("a_mask", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, datamask("b_mask", "customer", List.of("phone")), List.of(disabled)));
  }

  @Test
  void rejectsRowFilterOverlapAndUnknownTable() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "status = 'active'");
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, rf, List.of()));
    PolicyEntity rf2 = new PolicyEntity("rf2", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "id > 0");
    assertThrows(SqlMaskException.class, () -> validator.validatePolicy(INSTANCE, rf2, List.of(rf)));
  }

  @Test
  void rejectsMalformedFilterExpressionViaWhitelist() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "status = (SELECT status FROM t)");
    assertThrows(SqlMaskException.class, () -> validator.validatePolicy(INSTANCE, rf, List.of()));
  }

  @Test
  void rejectsBadInstanceNameDialectAndTypes() {
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("bad name!", "postgresql", INSTANCE.tables())));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("x", "oracle", INSTANCE.tables())));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("x", "trino", List.of(
            new TableDef("c", "s", "t", List.of(new ColumnDef("a", "datetime")))))));
  }
}
