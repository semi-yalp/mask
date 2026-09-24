package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyServiceImportTest {

  private final InMemoryPolicyStore store = new InMemoryPolicyStore();
  private final PolicyService service = new PolicyService(store, new PolicyValidator());

  @BeforeEach
  void setUpInstance() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer",
            List.of(new ColumnDef("id", "bigint"), new ColumnDef("phone", "varchar"),
                new ColumnDef("email", "varchar"))),
        new TableDef("crm", "public", "orders",
            List.of(new ColumnDef("id", "bigint"), new ColumnDef("status", "varchar")))));
    service.createUdf("pg_prod", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"))));
  }

  @Test
  void importsNewPoliciesAndUpsertsExistingByName() {
    service.createPolicy("pg_prod", new PolicyEntity("mask-email", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("email")),
        "mask_phone", List.of(1, 2), null));

    PolicyService.ImportResult result = service.importPolicies("pg_prod", """
        policies:
          - name: mask-phone
            priority: 0
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
                arguments: [3, 4]
          - name: mask-email
            priority: 5
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [email, phone]
            dataMaskItems:
              - groups: ["*"]
                udf: mask_phone
                arguments: [5, 6]
        """);

    assertEquals(1, result.created());
    assertEquals(1, result.updated());
    assertEquals(2, service.policies("pg_prod").size());
    PolicyEntity maskEmail = service.policies("pg_prod").stream()
        .filter(p -> p.name().equals("mask-email")).findFirst().orElseThrow();
    assertEquals(List.of("email", "phone"), maskEmail.resource().columns());
    assertEquals(List.of(5, 6), maskEmail.arguments());
  }

  @Test
  void rejectsWholeFileAndWritesNothingWhenAnyPolicyFailsValidation() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.importPolicies("pg_prod", """
        policies:
          - name: mask-phone
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
                arguments: [3, 4]
          - name: mask-unknown-udf
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [email]
            dataMaskItems:
              - users: ["*"]
                udf: no_such_udf
        """));

    assertTrue(e.getMessage().contains("unknown udf 'no_such_udf'"));
    assertTrue(service.policies("pg_prod").isEmpty(),
        "no policy from the file may be written when any of them fails validation");
  }

  @Test
  void rejectsGlobResource() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.importPolicies("pg_prod", """
        policies:
          - name: mask-all-columns
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: "*"
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
        """));

    assertTrue(e.getMessage().contains("glob '*' in resource is not supported"));
    assertTrue(service.policies("pg_prod").isEmpty());
  }

  @Test
  void rejectsOverlapAgainstExistingEnabledPolicy() {
    service.createPolicy("pg_prod", new PolicyEntity("mask-phone", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(1, 2), null));

    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.importPolicies("pg_prod", """
        policies:
          - name: mask-again
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
                arguments: [1, 2]
        """));

    assertTrue(e.getMessage().contains("overlaps enabled policy"));
    assertEquals(1, service.policies("pg_prod").size(), "the import must not be applied");
  }

  @Test
  void rejectsOverlapWithinTheImportedFile() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.importPolicies("pg_prod", """
        policies:
          - name: mask-a
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
                arguments: [1, 2]
          - name: mask-b
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone]
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
                arguments: [1, 2]
        """));

    assertTrue(e.getMessage().contains("mask-b"), e.getMessage());
    assertTrue(e.getMessage().contains("overlaps enabled policy"));
    assertTrue(service.policies("pg_prod").isEmpty(),
        "overlap between two imported policies rejects the whole file");
  }

  @Test
  void structuralYamlErrorsCarryTheSourcePath() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.importPolicies("pg_prod", "policies: [ {"));

    assertTrue(e.getMessage().startsWith("policies.yaml: invalid YAML"), e.getMessage());
  }

  @Test
  void unknownInstanceIsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.importPolicies("nope", "policies: []"));
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, e.getCode());
  }
}