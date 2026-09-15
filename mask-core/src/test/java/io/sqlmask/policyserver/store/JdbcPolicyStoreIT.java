package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the {@link InMemoryPolicyStoreTest} scenarios against
 * {@link JdbcPolicyStore} on a real PostgreSQL. Only executes when
 * {@code POLICY_PG_URL} is set (e.g. {@code jdbc:postgresql://127.0.0.1:5432/sqlmask_policy});
 * {@code POLICY_PG_USER}/{@code POLICY_PG_PASSWORD} default to
 * {@code sqlmask}/{@code sqlmask}. Applies {@code schema.sql} on start and
 * removes every instance it created after each test, so runs are repeatable
 * and never collide with leftover data (a unique per-run name suffix).
 */
@EnabledIfEnvironmentVariable(named = "POLICY_PG_URL", matches = ".+")
class JdbcPolicyStoreIT {

  private static final String UNIQUE_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

  private static DataSource dataSource;
  private static JdbcTemplate cleanupJdbc;

  private JdbcPolicyStore store;

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  @BeforeAll
  static void initDatabase() {
    dataSource = new DriverManagerDataSource(System.getenv("POLICY_PG_URL"),
        env("POLICY_PG_USER", "sqlmask"), env("POLICY_PG_PASSWORD", "sqlmask"));
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    cleanupJdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void createStore() {
    store = new JdbcPolicyStore(new JdbcTemplate(dataSource));
  }

  @AfterEach
  void cleanupInstances() {
    cleanupJdbc.update("DELETE FROM policy_instance WHERE name = ?", instanceName());
  }

  private static String instanceName() {
    return "pg_prod_" + UNIQUE_SUFFIX;
  }

  private static EngineInstance instance() {
    return new EngineInstance(instanceName(), "postgresql",
        List.of(new TableDef("crm", "public", "customer",
            List.of(new ColumnDef("phone", "varchar")))));
  }

  private static PolicyEntity datamask(String name) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null);
  }

  @Test
  void versionBumpsOnEveryMutation() {
    store.createInstance(instance());
    assertEquals(1, store.currentVersion(instanceName()));
    store.updateInstanceTables(instanceName(), instance().tables());
    assertEquals(2, store.currentVersion(instanceName()));
    store.createPolicy(instanceName(), datamask("p1"));
    assertEquals(3, store.currentVersion(instanceName()));
    store.updatePolicy(instanceName(), "p1",
        new PolicyEntity("p1", PolicyType.DATAMASK, false,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "mask_phone", List.of(3, 4), null));
    assertEquals(4, store.currentVersion(instanceName()));
    store.deletePolicy(instanceName(), "p1");
    assertEquals(5, store.currentVersion(instanceName()));
  }

  @Test
  void deleteInstanceBlockedWhilePoliciesExist() {
    store.createInstance(instance());
    store.createPolicy(instanceName(), datamask("p1"));
    SqlMaskException blocked = assertThrows(SqlMaskException.class,
        () -> store.deleteInstance(instanceName()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, blocked.getCode());
    assertEquals("instance '" + instanceName() + "' still has 1 policy(ies); delete them first",
        blocked.getMessage());
    assertEquals(2, store.currentVersion(instanceName()));
    store.deletePolicy(instanceName(), "p1");
    store.deleteInstance(instanceName());
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> store.currentVersion(instanceName())).getCode());
  }

  @Test
  void updatePolicyRejectsRenaming() {
    store.createInstance(instance());
    store.createPolicy(instanceName(), datamask("p1"));
    SqlMaskException rejected = assertThrows(SqlMaskException.class,
        () -> store.updatePolicy(instanceName(), "p1", datamask("p2")));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, rejected.getCode());
    assertEquals("policy name mismatch: 'p1' cannot be renamed to 'p2'", rejected.getMessage());
    assertEquals("p1", store.findPolicy(instanceName(), "p1").orElseThrow().name());
    assertEquals(2, store.currentVersion(instanceName()));
  }

  @Test
  void jsonbRoundTripKeepsScalarTypesAndOrdering() {
    EngineInstance created = new EngineInstance(instanceName(), "postgresql", List.of(
        new TableDef("crm", "public", "ztable",
            List.of(new ColumnDef("zz", "int"), new ColumnDef("aa", "text"))),
        new TableDef("crm", "public", "atable",
            List.of(new ColumnDef("m", "numeric(10,2)")))));
    assertEquals(created, store.createInstance(created));
    assertEquals(created, store.findInstance(instanceName()).orElseThrow());
    assertEquals(List.of(created), store.listInstances());
    assertEquals(1, store.currentVersion(instanceName()));

    SqlMaskException duplicateInstance = assertThrows(SqlMaskException.class,
        () -> store.createInstance(created));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, duplicateInstance.getCode());
    assertEquals("instance '" + instanceName() + "' already exists", duplicateInstance.getMessage());
    assertEquals(1, store.currentVersion(instanceName()));

    PolicyEntity datamask = new PolicyEntity("p1", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_between", Arrays.asList(3, 2.5, "abc", true), null);
    assertEquals(datamask, store.createPolicy(instanceName(), datamask));
    PolicyEntity loaded = store.findPolicy(instanceName(), "p1").orElseThrow();
    assertEquals(datamask, loaded);
    assertEquals(Integer.class, loaded.arguments().get(0).getClass());
    assertEquals(Double.class, loaded.arguments().get(1).getClass());
    assertEquals(String.class, loaded.arguments().get(2).getClass());
    assertEquals(Boolean.class, loaded.arguments().get(3).getClass());

    PolicyEntity rowFilter = new PolicyEntity("p2", PolicyType.ROW_FILTER, false,
        new ResourceSelector("crm", "public", "orders", List.of()),
        null, List.of(), "region = 'emea'");
    store.createPolicy(instanceName(), rowFilter);
    assertEquals(List.of(datamask, rowFilter), store.listPolicies(instanceName()));

    SqlMaskException duplicatePolicy = assertThrows(SqlMaskException.class,
        () -> store.createPolicy(instanceName(), datamask("p1")));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, duplicatePolicy.getCode());
    assertEquals("policy 'p1' already exists", duplicatePolicy.getMessage());

    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class,
            () -> store.findPolicy("no_such_instance_" + UNIQUE_SUFFIX, "p1")).getCode());
  }

  // ---- UDF CRUD（镜像 InMemory 场景） ----

  private static UdfDefinition udf() {
    return new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
        new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "varchar")));
  }

  @Test
  void udfCrudRoundTripAndVersionBumps() {
    store.createInstance(instance());
    long v1 = store.currentVersion(instanceName());
    store.createUdf(instanceName(), udf());
    assertEquals(v1 + 1, store.currentVersion(instanceName()));
    UdfDefinition loaded = store.findUdf(instanceName(), "mask_phone").orElseThrow();
    assertEquals(2, loaded.signatures().size());
    assertEquals(List.of("varchar", "integer", "integer"), loaded.signatures().get(0).params());
    assertEquals("bigint", loaded.signatures().get(1).params().get(0));
    store.replaceUdf(instanceName(), "mask_phone", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
    assertEquals(1, store.findUdf(instanceName(), "mask_phone").orElseThrow().signatures().size());
    assertEquals(v1 + 2, store.currentVersion(instanceName()));
    assertEquals(1, store.listUdfs(instanceName()).size());
    store.deleteUdf(instanceName(), "mask_phone");
    assertTrue(store.findUdf(instanceName(), "mask_phone").isEmpty());
    assertEquals(v1 + 3, store.currentVersion(instanceName()));
  }

  @Test
  void udfDuplicateAndMissingFollowErrorContract() {
    store.createInstance(instance());
    store.createUdf(instanceName(), udf());
    assertThrows(SqlMaskException.class,
        () -> store.createUdf(instanceName(), udf()));
    assertThrows(SqlMaskException.class,
        () -> store.deleteUdf(instanceName(), "nope"));
    assertThrows(SqlMaskException.class,
        () -> store.replaceUdf(instanceName(), "mask_phone",
            new UdfDefinition("renamed", List.of(
                new UdfDefinition.UdfSignature(List.of("varchar"), "varchar")))));
  }
}
