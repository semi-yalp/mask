package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryPolicyStoreTest {

  private final InMemoryPolicyStore store = new InMemoryPolicyStore();
  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer",
          List.of(new ColumnDef("phone", "varchar")))));

  private static PolicyEntity datamask(String name) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null);
  }

  @Test
  void versionBumpsOnEveryMutation() {
    store.createInstance(INSTANCE);
    assertEquals(1, store.currentVersion("pg_prod"));
    store.updateInstanceTables("pg_prod", INSTANCE.tables());
    assertEquals(2, store.currentVersion("pg_prod"));
    store.createPolicy("pg_prod", datamask("p1"));
    assertEquals(3, store.currentVersion("pg_prod"));
    store.updatePolicy("pg_prod", "p1",
        new PolicyEntity("p1", PolicyType.DATAMASK, false,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "mask_phone", List.of(3, 4), null));
    assertEquals(4, store.currentVersion("pg_prod"));
    store.deletePolicy("pg_prod", "p1");
    assertEquals(5, store.currentVersion("pg_prod"));
  }

  @Test
  void deleteInstanceBlockedWhilePoliciesExist() {
    store.createInstance(INSTANCE);
    store.createPolicy("pg_prod", datamask("p1"));
    assertThrows(SqlMaskException.class, () -> store.deleteInstance("pg_prod"));
    store.deletePolicy("pg_prod", "p1");
    store.deleteInstance("pg_prod");
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> store.currentVersion("pg_prod")).getCode());
  }

  @Test
  void updatePolicyRejectsRenaming() {
    store.createInstance(INSTANCE);
    store.createPolicy("pg_prod", datamask("p1"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.updatePolicy("pg_prod", "p1", datamask("p2"))).getCode());
    assertEquals("p1", store.findPolicy("pg_prod", "p1").orElseThrow().name());
    assertEquals(2, store.currentVersion("pg_prod"));
  }
}
