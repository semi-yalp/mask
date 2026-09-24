package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyServiceTest {

  private static final UdfDefinition MASK_PHONE = new UdfDefinition("mask_phone", List.of(
      new UdfDefinition.UdfSignature(List.of("varchar"), "varchar")));

  private final InMemoryPolicyStore store = new InMemoryPolicyStore();
  private final PolicyService service = new PolicyService(store, new PolicyValidator());

  @Test
  void updateMetadataRejectsRemovingReferencedTable() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
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
    service.createUdf("pg_prod", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"))));
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null));
    var response = service.effective("pg_prod", Subject.anonymous());
    assertEquals(1, response.config().columns().size());
    assertEquals("postgresql", response.dialect());
  }

  @Test
  void unknownInstanceMapsToNotFound() {
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class,
            () -> service.effective("nope", Subject.anonymous())).getCode());
  }

  @Test
  void effectiveReadsVersionBeforeListingPolicies() {
    OrderRecordingStore store = new OrderRecordingStore();
    PolicyService recordingService = new PolicyService(store, new PolicyValidator());
    recordingService.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    store.calls.clear();
    recordingService.effective("pg_prod", Subject.anonymous());
    assertTrue(store.calls.indexOf("currentVersion") < store.calls.indexOf("listPolicies"),
        "currentVersion must be read before listPolicies, got: " + store.calls);
  }

  @Test
  void effectiveFiltersBySubject() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    service.createPolicy("pg_prod", new PolicyEntity("only_alice", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("alice"), Set.of()), "mask_phone", List.of(), null));
    assertEquals(1, service.effective("pg_prod", Subject.of("alice", List.of()))
        .config().columns().size());
    assertEquals(0, service.effective("pg_prod", Subject.of("bob", List.of()))
        .config().columns().size());
  }

  // ---- UDF 服务面 ----

  @Test
  void createPolicyRejectsUnregisteredUdf() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "mask_phone", List.of(), null)));
    assertTrue(e.getMessage().contains("unknown udf 'mask_phone'"));
  }

  @Test
  void deleteUdfBlockedWhileEnabledPolicyReferencesIt() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    SqlMaskException blocked = assertThrows(SqlMaskException.class,
        () -> service.deleteUdf("pg_prod", "mask_phone"));
    assertTrue(blocked.getMessage().contains("disable them first"));
    // 替换为不兼容签名同样被拒
    SqlMaskException replaced = assertThrows(SqlMaskException.class,
        () -> service.replaceUdf("pg_prod", "mask_phone", new UdfDefinition("mask_phone",
            List.of(new UdfDefinition.UdfSignature(List.of("bigint"), "varchar")))));
    assertTrue(replaced.getMessage().contains("disable them first"));
    // 禁用后放行
    service.updatePolicy("pg_prod", "p", new PolicyEntity("p", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    service.deleteUdf("pg_prod", "mask_phone");
    assertTrue(service.udf("pg_prod", "mask_phone").isEmpty());
  }

  @Test
  void udfRegistrationDoesNotChangeEffectiveConfig() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    var before = service.effective("pg_prod", Subject.anonymous());
    long versionBefore = before.configVersion();
    service.replaceUdf("pg_prod", "mask_phone", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"),
        new UdfDefinition.UdfSignature(List.of("text"), "varchar"))));
    var after = service.effective("pg_prod", Subject.anonymous());
    assertEquals(before.config(), after.config());
    assertEquals(versionBefore + 1, after.configVersion());
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

  // ---- 写路径并发（M3）----

  @Test
  void concurrentSamePriorityOverlapCreatesExactlyOnePolicy() throws Exception {
    // M3：校验（enabledOthers 读 store）与写入是两个锁窗口——并发下双方都能
    // 看到对方的"不存在"，双双通过同优先级重叠校验后落库，触发运行期防御异常。
    // facade 串行化后，恰好一个成功，其余全部被 400 CONFIG_ERROR 拒绝。
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    int threads = 8;
    var latch = new java.util.concurrent.CountDownLatch(1);
    var successes = new java.util.concurrent.atomic.AtomicInteger();
    var configErrors = new java.util.concurrent.atomic.AtomicInteger();
    var surprises = new java.util.concurrent.CopyOnWriteArrayList<Exception>();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      final int n = i;
      pool.submit(() -> {
        try {
          latch.await();
          service.createPolicy("pg_prod", new PolicyEntity("p" + n, PolicyType.DATAMASK, true,
              new ResourceSelector("crm", "public", "customer", List.of("phone")),
              "mask_phone", List.of(), null));
          successes.incrementAndGet();
        } catch (SqlMaskException e) {
          if (e.getCode() == SqlMaskException.Code.CONFIG_ERROR) {
            configErrors.incrementAndGet();
          } else {
            surprises.add(e);
          }
        } catch (Exception e) {
          surprises.add(e);
        }
      });
    }
    latch.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1, successes.get(), () -> "surprises: " + surprises);
    assertEquals(threads - 1, configErrors.get(), () -> "surprises: " + surprises);
    assertTrue(surprises.isEmpty(), () -> "surprises: " + surprises);
  }

  @Test
  void deleteInstanceRacingCreatePolicyLeavesNoOrphanPolicy() throws Exception {
    // M3 级联陷阱的另一面：createPolicy 拿到 200 后策略必须真的可查——
    // facade 锁保证"requireInstance + 落库"与"deleteInstance"不交错
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createUdf("pg_prod", MASK_PHONE);
    int rounds = 40;
    for (int i = 0; i < rounds; i++) {
      var creator = new Thread(() -> {
        try {
          service.createPolicy("pg_prod", new PolicyEntity("race", PolicyType.DATAMASK, true,
              new ResourceSelector("crm", "public", "customer", List.of("phone")),
              "mask_phone", List.of(), null));
        } catch (SqlMaskException ignored) {
          // 实例已消失：允许的失败
        }
      });
      var deleter = new Thread(() -> {
        try {
          service.deleteInstance("pg_prod");
        } catch (SqlMaskException ignored) {
          // 仍有策略/不存在：允许的失败
        }
      });
      creator.start();
      deleter.start();
      creator.join();
      deleter.join();
      // 不变量：要么策略可查（实例在），要么实例不在且无策略
      boolean instancePresent = service.instances().stream()
          .anyMatch(inst -> inst.name().equals("pg_prod"));
      boolean policyPresent;
      try {
        policyPresent = service.policies("pg_prod").stream()
            .anyMatch(p -> p.name().equals("race"));
      } catch (SqlMaskException absent) {
        policyPresent = false; // 实例已删：策略必然不可查
      }
      if (instancePresent) {
        if (policyPresent) {
          service.deletePolicy("pg_prod", "race");
        }
      } else {
        assertTrue(!policyPresent, "policy survived without its instance");
      }
      // 复原基线供下一轮
      if (!instancePresent) {
        service.createInstance("pg_prod", "postgresql", List.of(
            new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
        service.createUdf("pg_prod", MASK_PHONE);
      }
    }
  }
}
