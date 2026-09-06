package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyServiceTest {

  private final PolicyService service =
      new PolicyService(new InMemoryPolicyStore(), new PolicyValidator());

  @Test
  void updateMetadataRejectsRemovingReferencedTable() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    assertThrows(SqlMaskException.class, () ->
        service.updateInstanceTables("pg_prod", List.of()));
    // 先禁用后可删
    service.updatePolicy("pg_prod", "p", new PolicyEntity("p", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    service.updateInstanceTables("pg_prod", List.of());
  }

  @Test
  void effectiveReturnsCompiledResponse() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null));
    var response = service.effective("pg_prod");
    assertEquals(1, response.config().columns().size());
    assertEquals("postgresql", response.dialect());
  }

  @Test
  void unknownInstanceMapsToNotFound() {
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> service.effective("nope")).getCode());
  }
}
