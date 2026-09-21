package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ChangeType;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPolicyStoreTest {

  private InMemoryPolicyStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryPolicyStore();
  }

  private static EngineInstance instance(String name) {
    TableDef t = new TableDef("crm", "public", "customer",
        List.of(new io.sqlmask.policyserver.model.ColumnDef("phone", "varchar")));
    return new EngineInstance(name, "postgresql", null, null, List.of(t));
  }

  private static PolicyEntity policy(String name, AccessType accessType, String udf,
      int version) {
    return new PolicyEntity(name, accessType, PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), udf, List.of(3, 4), null, version);
  }

  @Test
  void createInstanceThenCurrentVersionIsOne() {
    store.createInstance(instance("pg"));
    assertEquals(1L, store.currentVersion("pg"));
  }

  @Test
  void createInstanceDuplicateIsConfigError() {
    store.createInstance(instance("pg"));
    assertThrows(SqlMaskException.class, () -> store.createInstance(instance("pg")));
  }

  @Test
  void createPolicyWritesVersionOneAndHistoryRow() {
    store.createInstance(instance("pg"));
    PolicyEntity created = store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask", 0));
    assertEquals(1, created.currentVersion());
    List<PolicyVersion> history = store.policyVersions("pg", "p1");
    assertEquals(1, history.size());
    assertEquals(ChangeType.CREATE, history.get(0).changeType());
    assertEquals(2L, store.currentVersion("pg"));
  }

  @Test
  void updatePolicyWithoutConflictAdvancesVersion() {
    store.createInstance(instance("pg"));
    PolicyEntity created = store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask_a", 0));
    PolicyEntity updated = policy("p1", AccessType.SELECT, "mask_b", created.currentVersion());
    PolicyEntity result = store.updatePolicy("pg", "p1", updated);
    assertEquals(2, result.currentVersion());
    assertEquals("mask_b", result.udf());
    List<PolicyVersion> history = store.policyVersions("pg", "p1");
    assertEquals(2, history.size());
    assertEquals(ChangeType.UPDATE, history.get(1).changeType());
    assertEquals(3L, store.currentVersion("pg"));
  }

  @Test
  void updatePolicyWithStaleVersionIsConcurrentModification() {
    store.createInstance(instance("pg"));
    PolicyEntity created = store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask_a", 0));
    store.updatePolicy("pg", "p1", policy("p1", AccessType.SELECT, "mask_b", created.currentVersion()));
    // Now the stored version is 2; writing with version 1 must fail.
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        store.updatePolicy("pg", "p1", policy("p1", AccessType.SELECT, "mask_c", 1)));
    assertEquals(SqlMaskException.Code.CONCURRENT_MODIFICATION, e.getCode());
  }

  @Test
  void rollbackToEarlierVersionCreatesNewVersionWithOldContent() {
    store.createInstance(instance("pg"));
    PolicyEntity v1 = store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask_a", 0));
    PolicyEntity v2 = store.updatePolicy("pg", "p1",
        policy("p1", AccessType.SELECT, "mask_b", v1.currentVersion()));
    PolicyEntity rolled = store.rollbackPolicy("pg", "p1", 1);
    assertEquals(3, rolled.currentVersion());
    assertEquals("mask_a", rolled.udf());
    List<PolicyVersion> history = store.policyVersions("pg", "p1");
    assertEquals(3, history.size());
    PolicyVersion last = history.get(2);
    assertEquals(ChangeType.ROLLBACK, last.changeType());
    assertEquals(Integer.valueOf(1), last.sourceVersion());
  }

  @Test
  void rollbackToUnknownVersionIsVersionNotFound() {
    store.createInstance(instance("pg"));
    store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask", 0));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        store.rollbackPolicy("pg", "p1", 99));
    assertEquals(SqlMaskException.Code.VERSION_NOT_FOUND, e.getCode());
  }

  @Test
  void operationsOnUnknownInstanceFailClosed() {
    assertThrows(SqlMaskException.class, () -> store.createPolicy("nope",
        policy("p1", AccessType.SELECT, "mask", 0)));
    assertThrows(SqlMaskException.class, () -> store.currentVersion("nope"));
    assertThrows(SqlMaskException.class, () -> store.updateInstanceTables("nope", List.of()));
  }

  @Test
  void deletePolicyKeepsHistoryAndBumps() {
    store.createInstance(instance("pg"));
    store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask", 0));
    long before = store.currentVersion("pg");
    store.deletePolicy("pg", "p1");
    assertEquals(before + 1, store.currentVersion("pg"));
    assertTrue(store.findPolicy("pg", "p1").isEmpty());
    assertEquals(1, store.policyVersions("pg", "p1").size());
  }

  @Test
  void deleteInstanceWithPoliciesIsConfigError() {
    store.createInstance(instance("pg"));
    store.createPolicy("pg", policy("p1", AccessType.SELECT, "mask", 0));
    assertThrows(SqlMaskException.class, () -> store.deleteInstance("pg"));
  }

  @Test
  void updateConnectionAndTablesReplaceStateAndBump() {
    store.createInstance(instance("pg"));
    ConnectionConfig cfg = new ConnectionConfig("postgresql", "localhost", 5432, "db",
        "user", "PW_REF", List.of(), false, "disable", 15);
    long before = store.currentVersion("pg");
    EngineInstance connected = store.updateInstanceConnection("pg", cfg, ConnectionStatus.CONNECTED);
    assertEquals(ConnectionStatus.CONNECTED, connected.status());
    assertEquals(cfg, connected.connection());
    assertEquals(before + 1, store.currentVersion("pg"));
    TableDef t = new TableDef("crm", "public", "orders",
        List.of(new io.sqlmask.policyserver.model.ColumnDef("amount", "numeric")));
    EngineInstance replaced = store.updateInstanceTables("pg", List.of(t));
    assertEquals(1, replaced.tables().size());
    assertEquals("orders", replaced.tables().get(0).name());
  }

  @Test
  void udfCrudRegistersAndBumps() {
    store.createInstance(instance("pg"));
    UdfDefinition udf = new UdfDefinition("mask_phone",
        List.of(new UdfDefinition.UdfSignature(List.of("varchar"), "varchar")));
    store.createUdf("pg", udf);
    assertEquals(1, store.listUdfs("pg").size());
    long before = store.currentVersion("pg");
    store.deleteUdf("pg", "mask_phone");
    assertEquals(before + 1, store.currentVersion("pg"));
    assertFalse(store.findUdf("pg", "mask_phone").isPresent());
  }
}