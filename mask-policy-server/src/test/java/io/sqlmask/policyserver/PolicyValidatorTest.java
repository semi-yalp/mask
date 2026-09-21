package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyValidatorTest {

  private PolicyValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PolicyValidator();
  }

  private static EngineInstance instance(TableDef... tables) {
    return new EngineInstance("pg", "postgresql", null, null, List.of(tables));
  }

  private static TableDef customerTable() {
    return new TableDef("crm", "public", "customer",
        List.of(new ColumnDef("phone", "varchar"), new ColumnDef("id", "bigint")));
  }

  private static List<UdfDefinition> udfs() {
    return List.of(new UdfDefinition("mask_phone",
        List.of(new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
  }

  private static PolicyEntity datamask(String name, ResourceSelector resource, String columns,
      List<UdfDefinition> ignoredUdfs) {
    return new PolicyEntity(name, AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        resource, new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0);
  }

  private static ResourceSelector exactTable() {
    return new ResourceSelector("crm", "public", "customer", List.of("phone"));
  }

  @Test
  void validExactDatamaskProducesNoWarnings() {
    List<String> warnings = validator.validatePolicy(instance(customerTable()), udfs(),
        datamask("p1", exactTable(), "phone", udfs()), List.of());
    assertEquals(List.of(), warnings);
  }

  @Test
  void selectAccessTypePasses() {
    validator.validatePolicy(instance(customerTable()), udfs(),
        new PolicyEntity("p1", null, PolicyType.DATAMASK, true, 0, exactTable(),
            new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0),
        List.of());
  }

  @Test
  void globTableIsAcceptedWithWarning() {
    PolicyEntity glob = new PolicyEntity("p1", AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer*", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0);
    List<String> warnings = validator.validatePolicy(instance(customerTable()), udfs(), glob,
        List.of());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("glob")));
  }

  @Test
  void globColumnIsAccepted() {
    PolicyEntity globColumn = new PolicyEntity("p1", AccessType.SELECT, PolicyType.DATAMASK, true,
        0, new ResourceSelector("crm", "public", "customer", List.of("phone*")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0);
    validator.validatePolicy(instance(customerTable()), udfs(), globColumn, List.of());
  }

  @Test
  void unknownExactTableIsWarningNotError() {
    PolicyEntity p = datamask("p1",
        new ResourceSelector("crm", "public", "nonexistent", List.of("phone")), null, udfs());
    List<String> warnings = validator.validatePolicy(instance(customerTable()), udfs(), p,
        List.of());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("not in the instance snapshot")));
  }

  @Test
  void unknownColumnIsWarningNotError() {
    PolicyEntity p = datamask("p1",
        new ResourceSelector("crm", "public", "customer", List.of("nope")), null, udfs());
    List<String> warnings = validator.validatePolicy(instance(customerTable()), udfs(), p,
        List.of());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown column 'nope'")));
  }

  @Test
  void unknownUdfIsHardError() {
    PolicyEntity p = new PolicyEntity("p1", AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        exactTable(), new SubjectSelector(Set.of("*"), Set.of()), "missing_udf", List.of(), null, 0);
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(instance(customerTable()), udfs(), p, List.of()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("unknown udf"));
  }

  @Test
  void syntaxMismatchedUdfIsHardError() {
    // mask_phone(varchar)->varchar cannot take an integer argument position.
    PolicyEntity p = new PolicyEntity("p1", AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        exactTable(), new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(1), null, 0);
    assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(instance(customerTable()), udfs(), p, List.of()));
  }

  @Test
  void columnTypeUnknownSkipsOverloadWithWarning() {
    // Table missing from snapshot → column type unknown → warning, no hard reject.
    PolicyEntity p = datamask("p1",
        new ResourceSelector("crm", "public", "ghost", List.of("phone")), null, udfs());
    List<String> warnings = validator.validatePolicy(instance(customerTable()), udfs(), p,
        List.of());
    assertTrue(warnings.stream().anyMatch(w -> w.contains("udf overload matching skipped")
        || w.contains("not in the instance snapshot")));
  }

  @Test
  void overlapRejectedOnlyForExactResources() {
    List<PolicyEntity> other = List.of(
        new PolicyEntity("existing", AccessType.SELECT, PolicyType.DATAMASK, true, 0, exactTable(),
            new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0));
    PolicyEntity same = new PolicyEntity("new", AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        exactTable(), new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0);
    assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(instance(customerTable()), udfs(), same, other));
  }

  @Test
  void globResourceSkipsOverlapRejection() {
    PolicyEntity other = new PolicyEntity("existing", AccessType.SELECT, PolicyType.DATAMASK, true,
        0, exactTable(), new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null,
        0);
    PolicyEntity glob = new PolicyEntity("new", AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "custom*", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, 0);
    validator.validatePolicy(instance(customerTable()), udfs(), glob, List.of(other));
  }

  @Test
  void rowFilterWhitelistEnforced() {
    PolicyEntity ok = new PolicyEntity("rf", AccessType.SELECT, PolicyType.ROW_FILTER, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), "id > 100", 0);
    validator.validatePolicy(instance(customerTable()), udfs(), ok, List.of());
    PolicyEntity bad = new PolicyEntity("rf2", AccessType.SELECT, PolicyType.ROW_FILTER, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("*"), Set.of()), null, List.of(), "now() > id", 0);
    assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(instance(customerTable()), udfs(), bad, List.of()));
  }

  @Test
  void validateInstanceRejectsConnectionDialectMismatch() {
    EngineInstance mismatch = new EngineInstance("pg", "postgresql",
        new ConnectionConfig("mysql", "localhost", 3306, "db", "u", "PW_REF", List.of(), false,
            "disable", 15),
        null, List.of(customerTable()));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(mismatch));
  }

  @Test
  void validateInstanceValid() {
    validator.validateInstance(instance(customerTable()));
  }

  @Test
  void validateUdfRejectsInvalidTypeDeclaration() {
    UdfDefinition bad = new UdfDefinition("bad_udf",
        List.of(new UdfDefinition.UdfSignature(List.of("notatype"), "varchar")));
    assertThrows(SqlMaskException.class, () -> validator.validateUdf(instance(customerTable()), bad));
  }
}