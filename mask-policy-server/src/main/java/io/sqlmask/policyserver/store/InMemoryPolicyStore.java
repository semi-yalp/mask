package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ChangeType;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link PolicyStore} used for tests and fallback: identical
 * semantics to the JDBC store, no persistence. State lives in synchronized
 * insertion-ordered maps; the config version starts at 1 and advances on every
 * mutation.
 */
public final class InMemoryPolicyStore implements PolicyStore {

  private final Map<String, EngineInstance> instances = new LinkedHashMap<>();
  private final Map<String, Map<String, PolicyEntity>> policiesByInstance = new LinkedHashMap<>();
  private final Map<String, Map<String, Map<Integer, PolicyVersion>>> historyByInstance =
      new LinkedHashMap<>();
  private final Map<String, Map<String, UdfDefinition>> udfsByInstance = new LinkedHashMap<>();
  private final Map<String, Long> versions = new LinkedHashMap<>();

  @Override
  public synchronized EngineInstance createInstance(EngineInstance instance) {
    if (instances.containsKey(instance.name())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + instance.name() + "' already exists");
    }
    instances.put(instance.name(), instance);
    policiesByInstance.put(instance.name(), new LinkedHashMap<>());
    historyByInstance.put(instance.name(), new LinkedHashMap<>());
    udfsByInstance.put(instance.name(), new LinkedHashMap<>());
    versions.put(instance.name(), 1L);
    return instance;
  }

  @Override
  public synchronized EngineInstance updateInstanceConnection(String name, ConnectionConfig cfg,
      ConnectionStatus status) {
    EngineInstance current = requireInstance(name);
    EngineInstance updated = new EngineInstance(name, current.dialect(), cfg, status,
        current.tables());
    instances.put(name, updated);
    bump(name);
    return updated;
  }

  @Override
  public synchronized EngineInstance updateInstanceTables(String name, List<TableDef> tables) {
    EngineInstance current = requireInstance(name);
    EngineInstance updated = new EngineInstance(name, current.dialect(), current.connection(),
        current.status(), tables == null ? List.of() : List.copyOf(tables));
    instances.put(name, updated);
    bump(name);
    return updated;
  }

  @Override
  public synchronized Optional<EngineInstance> findInstance(String name) {
    return Optional.ofNullable(instances.get(name));
  }

  @Override
  public synchronized List<EngineInstance> listInstances() {
    return List.copyOf(instances.values());
  }

  @Override
  public synchronized void deleteInstance(String name) {
    requireInstance(name);
    if (!policiesByInstance.get(name).isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "' still has policies; delete them first");
    }
    instances.remove(name);
    policiesByInstance.remove(name);
    historyByInstance.remove(name);
    udfsByInstance.remove(name);
    versions.remove(name);
  }

  @Override
  public synchronized PolicyEntity createPolicy(String instanceName, PolicyEntity policy) {
    requireInstance(instanceName);
    Map<String, PolicyEntity> policies = policiesByInstance.get(instanceName);
    if (policies.containsKey(policy.name())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policy.name() + "' already exists in instance '" + instanceName + "'");
    }
    // A deleted policy leaves history behind; a recreate continues the sequence.
    int nextVersion = historyOf(instanceName, policy.name()).keySet().stream()
        .mapToInt(Integer::intValue).max().orElse(0) + 1;
    PolicyEntity created = withVersion(policy, nextVersion);
    policies.put(created.name(), created);
    appendHistory(instanceName, created.name(), nextVersion, ChangeType.CREATE, created, null);
    bump(instanceName);
    return created;
  }

  @Override
  public synchronized PolicyEntity updatePolicy(String instanceName, String policyName,
      PolicyEntity policy) {
    Map<String, PolicyEntity> policies = policiesOf(instanceName, policyName);
    if (!policy.name().equals(policyName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy name mismatch: '" + policyName + "' cannot be renamed to '" + policy.name() + "'");
    }
    PolicyEntity current = policies.get(policyName);
    if (current.currentVersion() != policy.currentVersion()) {
      throw new SqlMaskException(SqlMaskException.Code.CONCURRENT_MODIFICATION,
          "policy '" + policyName + "' was modified concurrently: expected version "
              + current.currentVersion() + " but got " + policy.currentVersion()
              + "; reload and retry");
    }
    int newVersion = current.currentVersion() + 1;
    PolicyEntity updated = withVersion(policy, newVersion);
    policies.put(policyName, updated);
    appendHistory(instanceName, policyName, newVersion, ChangeType.UPDATE, updated, null);
    bump(instanceName);
    return updated;
  }

  @Override
  public synchronized PolicyEntity rollbackPolicy(String instanceName, String policyName,
      int targetVersion) {
    Map<String, PolicyEntity> policies = policiesOf(instanceName, policyName);
    Map<Integer, PolicyVersion> history = historyOf(instanceName, policyName);
    PolicyVersion target = history.get(targetVersion);
    if (target == null) {
      throw new SqlMaskException(SqlMaskException.Code.VERSION_NOT_FOUND,
          "policy '" + policyName + "' has no version " + targetVersion);
    }
    PolicyEntity current = policies.get(policyName);
    int newVersion = current.currentVersion() + 1;
    PolicyEntity rolled = withVersion(target.content(), newVersion);
    policies.put(policyName, rolled);
    appendHistory(instanceName, policyName, newVersion, ChangeType.ROLLBACK, rolled,
        targetVersion);
    bump(instanceName);
    return rolled;
  }

  @Override
  public synchronized Optional<PolicyEntity> findPolicy(String instanceName, String policyName) {
    Map<String, PolicyEntity> policies = policiesByInstance.get(instanceName);
    if (policies == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(policies.get(policyName));
  }

  @Override
  public synchronized List<PolicyEntity> listPolicies(String instanceName) {
    return policiesOf(instanceName).values().stream().toList();
  }

  @Override
  public synchronized List<PolicyVersion> policyVersions(String instanceName, String policyName) {
    if (!historyByInstance.containsKey(instanceName)) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + instanceName + "' not found");
    }
    // Unknown policy name → empty history (matches the JDBC store's contract).
    Map<Integer, PolicyVersion> history = historyByInstance.get(instanceName).get(policyName);
    if (history == null) {
      return List.of();
    }
    return history.values().stream()
        .sorted(Comparator.comparingInt(PolicyVersion::version))
        .toList();
  }

  @Override
  public synchronized void deletePolicy(String instanceName, String policyName) {
    Map<String, PolicyEntity> policies = policiesOf(instanceName, policyName);
    policies.remove(policyName);
    bump(instanceName);
  }

  @Override
  public synchronized UdfDefinition createUdf(String instanceName, UdfDefinition udf) {
    Map<String, UdfDefinition> udfs = requireInstance(instanceName, udfsByInstance);
    if (udfs.containsKey(udf.name())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + udf.name() + "' already exists in instance '" + instanceName + "'");
    }
    udfs.put(udf.name(), udf);
    bump(instanceName);
    return udf;
  }

  @Override
  public synchronized UdfDefinition replaceUdf(String instanceName, String udfName,
      UdfDefinition udf) {
    Map<String, UdfDefinition> udfs = requireInstance(instanceName, udfsByInstance);
    if (!udfs.containsKey(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + udfName + "' not found in instance '" + instanceName + "'");
    }
    udfs.put(udfName, udf);
    bump(instanceName);
    return udf;
  }

  @Override
  public synchronized Optional<UdfDefinition> findUdf(String instanceName, String udfName) {
    Map<String, UdfDefinition> udfs = udfsByInstance.get(instanceName);
    if (udfs == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(udfs.get(udfName));
  }

  @Override
  public synchronized List<UdfDefinition> listUdfs(String instanceName) {
    return requireInstance(instanceName, udfsByInstance).values().stream().toList();
  }

  @Override
  public synchronized void deleteUdf(String instanceName, String udfName) {
    Map<String, UdfDefinition> udfs = requireInstance(instanceName, udfsByInstance);
    if (!udfs.containsKey(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "udf '" + udfName + "' not found in instance '" + instanceName + "'");
    }
    udfs.remove(udfName);
    bump(instanceName);
  }

  @Override
  public synchronized long currentVersion(String instanceName) {
    Long version = versions.get(instanceName);
    if (version == null) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + instanceName + "' not found");
    }
    return version;
  }

  private EngineInstance requireInstance(String name) {
    EngineInstance instance = instances.get(name);
    if (instance == null) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + name + "' not found");
    }
    return instance;
  }

  private Map<String, PolicyEntity> policiesOf(String instanceName, String policyName) {
    Map<String, PolicyEntity> policies = policiesOf(instanceName);
    if (!policies.containsKey(policyName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policyName + "' not found in instance '" + instanceName + "'");
    }
    return policies;
  }

  private Map<String, PolicyEntity> policiesOf(String instanceName) {
    Map<String, PolicyEntity> policies = policiesByInstance.get(instanceName);
    if (policies == null) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + instanceName + "' not found");
    }
    return policies;
  }

  private Map<Integer, PolicyVersion> historyOf(String instanceName, String policyName) {
    return historyByInstance.get(instanceName).computeIfAbsent(policyName,
        ignored -> new LinkedHashMap<>());
  }

  private void appendHistory(String instanceName, String policyName, int version,
      ChangeType changeType, PolicyEntity content, Integer sourceVersion) {
    historyOf(instanceName, policyName).put(version,
        new PolicyVersion(version, changeType, content, sourceVersion, Instant.now()));
  }

  private <T> Map<String, T> requireInstance(String instanceName, Map<String, Map<String, T>> index) {
    Map<String, T> map = index.get(instanceName);
    if (map == null) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + instanceName + "' not found");
    }
    return map;
  }

  private static PolicyEntity withVersion(PolicyEntity policy, int version) {
    return new PolicyEntity(policy.name(), policy.accessType(), policy.policyType(),
        policy.enabled(), policy.priority(), policy.resource(), policy.subjects(), policy.udf(),
        policy.arguments(), policy.filterExpr(), version);
  }

  private void bump(String instanceName) {
    versions.compute(instanceName, (ignored, v) -> v == null ? 2L : v + 1);
  }
}