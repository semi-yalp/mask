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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void effectiveReadsVersionBeforeListingPolicies() {
    OrderRecordingStore store = new OrderRecordingStore();
    PolicyService recordingService = new PolicyService(store, new PolicyValidator());
    recordingService.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    store.calls.clear();
    recordingService.effective("pg_prod");
    assertTrue(store.calls.indexOf("currentVersion") < store.calls.indexOf("listPolicies"),
        "currentVersion must be read before listPolicies, got: " + store.calls);
  }

  /** Records the relative order of the two calls effective() makes against the store. */
  private static final class OrderRecordingStore extends InMemoryPolicyStore {
    final List<String> calls = new ArrayList<>();

    @Override
    public long currentVersion(String instanceName) {
      calls.add("currentVersion");
      return super.currentVersion(instanceName);
    }

    @Override
    public List<PolicyEntity> listPolicies(String instanceName) {
      calls.add("listPolicies");
      return super.listPolicies(instanceName);
    }
  }
}
