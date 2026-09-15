package io.sqlmask.policyserver.store;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  // ---- UDF CRUD ----

  private static UdfDefinition udf(String name, String... params) {
    return new UdfDefinition(name, List.of(
        new UdfDefinition.UdfSignature(List.of(params), "varchar")));
  }

  @Test
  void udfCrudRoundTripAndVersionBumps() {
    store.createInstance(INSTANCE);
    assertEquals(1, store.currentVersion("pg_prod"));
    store.createUdf("pg_prod", udf("mask_phone", "varchar", "integer", "integer"));
    assertEquals(2, store.currentVersion("pg_prod"));
    assertEquals("mask_phone", store.findUdf("pg_prod", "mask_phone").orElseThrow().name());
    store.replaceUdf("pg_prod", "mask_phone",
        new UdfDefinition("mask_phone", List.of(
            new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"),
            new UdfDefinition.UdfSignature(List.of("bigint"), "varchar"))));
    assertEquals(2, store.findUdf("pg_prod", "mask_phone").orElseThrow().signatures().size());
    assertEquals(3, store.currentVersion("pg_prod"));
    assertEquals(1, store.listUdfs("pg_prod").size());
    store.deleteUdf("pg_prod", "mask_phone");
    assertTrue(store.findUdf("pg_prod", "mask_phone").isEmpty());
    assertEquals(4, store.currentVersion("pg_prod"));
  }

  @Test
  void udfErrorsFollowStoreContract() {
    store.createInstance(INSTANCE);
    store.createUdf("pg_prod", udf("mask_phone", "varchar"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.createUdf("pg_prod", udf("mask_phone", "varchar"))).getCode());
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.replaceUdf("pg_prod", "mask_phone", udf("renamed", "varchar")))
            .getCode());
    assertEquals(SqlMaskException.Code.CONFIG_ERROR,
        assertThrows(SqlMaskException.class,
            () -> store.deleteUdf("pg_prod", "nope")).getCode());
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> store.createUdf("nope", udf("u", "varchar")))
            .getCode());
    assertEquals(2, store.currentVersion("pg_prod")); // 失败变更不推进版本
  }
}
