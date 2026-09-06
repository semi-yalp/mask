package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.TableDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single-node {@link PolicyStore}: plain maps guarded by {@code synchronized}
 * on every method, declaration-order preservation included. Every successful
 * mutation advances the instance's {@code config_version} by one (it starts at
 * 1 when the instance is created).
 */
public class InMemoryPolicyStore implements PolicyStore {

  private final Map<String, EngineInstance> instances = new LinkedHashMap<>();
  private final Map<String, Map<String, PolicyEntity>> policiesByInstance = new LinkedHashMap<>();
  private final Map<String, Long> versions = new LinkedHashMap<>();

  @Override
  public synchronized EngineInstance createInstance(EngineInstance instance) {
    if (instances.containsKey(instance.name())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + instance.name() + "' already exists");
    }
    instances.put(instance.name(), instance);
    policiesByInstance.put(instance.name(), new LinkedHashMap<>());
    bump(instance.name());
    return instance;
  }

  @Override
  public synchronized EngineInstance updateInstanceTables(String name, List<TableDef> tables) {
    EngineInstance existing = requireInstance(name);
    EngineInstance updated = new EngineInstance(existing.name(), existing.dialect(), tables);
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
    int policyCount = policiesByInstance.get(name).size();
    if (policyCount > 0) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "instance '" + name
          + "' still has " + policyCount + " policy(ies); delete them first");
    }
    instances.remove(name);
    policiesByInstance.remove(name);
    versions.remove(name);
  }

  @Override
  public synchronized PolicyEntity createPolicy(String instanceName, PolicyEntity policy) {
    requireInstance(instanceName);
    Map<String, PolicyEntity> policies = policiesByInstance.get(instanceName);
    if (policies.containsKey(policy.name())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policy.name() + "' already exists");
    }
    policies.put(policy.name(), policy);
    bump(instanceName);
    return policy;
  }

  @Override
  public synchronized PolicyEntity updatePolicy(String instanceName, String policyName,
      PolicyEntity policy) {
    requireInstance(instanceName);
    Map<String, PolicyEntity> policies = policiesByInstance.get(instanceName);
    if (!policies.containsKey(policyName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policyName + "' not found");
    }
    policies.put(policyName, policy);
    bump(instanceName);
    return policy;
  }

  @Override
  public synchronized Optional<PolicyEntity> findPolicy(String instanceName, String policyName) {
    requireInstance(instanceName);
    return Optional.ofNullable(policiesByInstance.get(instanceName).get(policyName));
  }

  @Override
  public synchronized List<PolicyEntity> listPolicies(String instanceName) {
    requireInstance(instanceName);
    return List.copyOf(policiesByInstance.get(instanceName).values());
  }

  @Override
  public synchronized void deletePolicy(String instanceName, String policyName) {
    requireInstance(instanceName);
    Map<String, PolicyEntity> policies = policiesByInstance.get(instanceName);
    if (policies.remove(policyName) == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policyName + "' not found");
    }
    bump(instanceName);
  }

  @Override
  public synchronized long currentVersion(String instanceName) {
    requireInstance(instanceName);
    return versions.get(instanceName);
  }

  private EngineInstance requireInstance(String name) {
    EngineInstance instance = instances.get(name);
    if (instance == null) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + name + "' not found");
    }
    return instance;
  }

  private void bump(String instanceName) {
    versions.merge(instanceName, 1L, Long::sum);
  }
}
