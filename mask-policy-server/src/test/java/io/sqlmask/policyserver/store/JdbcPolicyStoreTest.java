package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ChangeType;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the {@link InMemoryPolicyStoreTest} scenarios against
 * {@link JdbcPolicyStore} on a real PostgreSQL (embedded, or an existing one
 * via {@code POLICY_PG_URL}). Applies {@code schema.sql} on start and cleans
 * up per-test via a unique instance-name suffix.
 */
class JdbcPolicyStoreTest {

  private static final String UNIQUE_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static EmbeddedPostgres embedded;
  private static DataSource dataSource;
  private static JdbcTemplate cleanupJdbc;

  private JdbcPolicyStore store;
  private String instanceName;

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  @BeforeAll
  static void initDatabase() throws IOException {
    String url = System.getenv("POLICY_PG_URL");
    if (url != null && !url.isBlank()) {
      dataSource = new DriverManagerDataSource(url,
          env("POLICY_PG_USER", "sqlmask"), env("POLICY_PG_PASSWORD", "sqlmask"));
    } else {
      embedded = EmbeddedPostgres.builder().start();
      dataSource = embedded.getPostgresDatabase();
    }
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    cleanupJdbc = new JdbcTemplate(dataSource);
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    if (embedded != null) {
      embedded.close();
    }
  }

  @BeforeEach
  void createStore() {
    instanceName = "pg_prod_" + UNIQUE_SUFFIX;
    store = new JdbcPolicyStore(new JdbcTemplate(dataSource));
    store.createInstance(instance());
  }

  @AfterEach
  void cleanupInstances() {
    cleanupJdbc.update("DELETE FROM policy_instance WHERE name = ?", instanceName);
  }

  private static EngineInstance instance() {
    TableDef t = new TableDef("crm", "public", "customer",
        List.of(new ColumnDef("phone", "varchar")));
    return new EngineInstance("pg_prod_" + UNIQUE_SUFFIX, "postgresql", null, null, List.of(t));
  }

  private static PolicyEntity policy(String name, String udf, int version) {
    return new PolicyEntity(name, AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), udf, List.of(3, 4), null, version);
  }

  @Test
  void createInstanceThenCurrentVersionIsOne() {
    assertEquals(1L, store.currentVersion(instanceName));
  }

  @Test
  void createInstanceDuplicateIsConfigError() {
    assertThrows(SqlMaskException.class, () -> store.createInstance(instance()));
  }

  @Test
  void createPolicyWritesVersionOneAndHistoryRow() {
    PolicyEntity created = store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    assertEquals(1, created.currentVersion());
    List<PolicyVersion> history = store.policyVersions(instanceName, "p1");
    assertEquals(1, history.size());
    assertEquals(ChangeType.CREATE, history.get(0).changeType());
  }

  @Test
  void updatePolicyWithoutConflictAdvancesVersion() {
    PolicyEntity created = store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    PolicyEntity updated = store.updatePolicy(instanceName, "p1",
        policy("p1", "mask_b", created.currentVersion()));
    assertEquals(2, updated.currentVersion());
    assertEquals("mask_b", updated.udf());
    assertEquals(2, store.policyVersions(instanceName, "p1").size());
  }

  @Test
  void updatePolicyWithStaleVersionIsConcurrentModification() {
    PolicyEntity created = store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    store.updatePolicy(instanceName, "p1",
        policy("p1", "mask_b", created.currentVersion()));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        store.updatePolicy(instanceName, "p1", policy("p1", "mask_c", 1)));
    assertEquals(SqlMaskException.Code.CONCURRENT_MODIFICATION, e.getCode());
  }

  @Test
  void rollbackToEarlierVersionCreatesNewVersionWithOldContent() {
    PolicyEntity v1 = store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    store.updatePolicy(instanceName, "p1", policy("p1", "mask_b", v1.currentVersion()));
    PolicyEntity rolled = store.rollbackPolicy(instanceName, "p1", 1);
    assertEquals(3, rolled.currentVersion());
    assertEquals("mask_a", rolled.udf());
    PolicyVersion last = store.policyVersions(instanceName, "p1").get(2);
    assertEquals(ChangeType.ROLLBACK, last.changeType());
    assertEquals(Integer.valueOf(1), last.sourceVersion());
  }

  @Test
  void rollbackToUnknownVersionIsVersionNotFound() {
    store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        store.rollbackPolicy(instanceName, "p1", 99));
    assertEquals(SqlMaskException.Code.VERSION_NOT_FOUND, e.getCode());
  }

  @Test
  void deletePolicyKeepsHistoryAndBumps() {
    store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    long before = store.currentVersion(instanceName);
    store.deletePolicy(instanceName, "p1");
    assertEquals(before + 1, store.currentVersion(instanceName));
    assertTrue(store.findPolicy(instanceName, "p1").isEmpty());
    assertEquals(1, store.policyVersions(instanceName, "p1").size());
  }

  @Test
  void deleteInstanceWithPoliciesIsConfigError() {
    store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    assertThrows(SqlMaskException.class, () -> store.deleteInstance(instanceName));
  }

  @Test
  void connectionAndStatusRoundTrip() {
    ConnectionConfig cfg = new ConnectionConfig("postgresql", "db.internal", 5432, "shop",
        "svc", "SHOP_PW_REF", List.of("public"), false, "require", 15);
    store.updateInstanceConnection(instanceName, cfg, ConnectionStatus.CONNECTED);
    EngineInstance loaded = store.findInstance(instanceName).orElseThrow();
    assertEquals(ConnectionStatus.CONNECTED, loaded.status());
    assertEquals(cfg, loaded.connection());
  }

  @Test
  void accessTypeAndCurrentVersionRoundTrip() {
    PolicyEntity created = store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    PolicyEntity loaded = store.findPolicy(instanceName, "p1").orElseThrow();
    assertEquals(AccessType.SELECT, loaded.accessType());
    assertEquals(created.currentVersion(), loaded.currentVersion());
  }

  @Test
  void nullSubjectsRowReadsAsEveryone() {
    store.createPolicy(instanceName, policy("p1", "mask_a", 0));
    store.updatePolicy(instanceName, "p1", policy("p1", "mask_b", 1));
    cleanupJdbc.update("UPDATE policy SET subjects = NULL WHERE name = ? AND instance_id = "
        + "(SELECT id FROM policy_instance WHERE name = ?)", "p1", instanceName);
    PolicyEntity loaded = store.findPolicy(instanceName, "p1").orElseThrow();
    assertEquals(new SubjectSelector(Set.of("*"), Set.of()), loaded.subjects());
  }

  @Test
  void udfCrudRoundTrip() {
    UdfDefinition udf = new UdfDefinition("mask_phone",
        List.of(new UdfDefinition.UdfSignature(List.of("varchar"), "varchar")));
    store.createUdf(instanceName, udf);
    UdfDefinition loaded = store.findUdf(instanceName, "mask_phone").orElseThrow();
    assertEquals(1, loaded.signatures().size());
    assertEquals("varchar", loaded.signatures().get(0).returns());
    store.deleteUdf(instanceName, "mask_phone");
    assertFalse(store.findUdf(instanceName, "mask_phone").isPresent());
  }
}