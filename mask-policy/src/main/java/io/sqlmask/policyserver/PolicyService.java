package io.sqlmask.policyserver;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policyserver.compile.EffectiveConfigCompiler;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.store.PolicyStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Write-path facade over {@link PolicyStore}: every mutation validates first
 * (via {@link PolicyValidator}) so the store only ever holds self-consistent
 * state, and {@link #effective} compiles the stored state into the wire
 * contract stamped with the store's current {@code config_version}.
 */
public class PolicyService {

  private final PolicyStore store;
  private final PolicyValidator validator;

  public PolicyService(PolicyStore store, PolicyValidator validator) {
    this.store = store;
    this.validator = validator;
  }

  public EngineInstance createInstance(String name, String dialect, List<TableDef> tables) {
    EngineInstance instance = new EngineInstance(name, dialect, tables);
    validator.validateInstance(instance);
    return store.createInstance(instance);
  }

  public EngineInstance updateInstanceTables(String name, List<TableDef> tables) {
    EngineInstance current = requireInstance(name);
    EngineInstance updated = new EngineInstance(name, current.dialect(), tables);
    validator.validateInstance(updated);
    ensureEnabledPoliciesResolve(name, updated);
    return store.updateInstanceTables(name, tables);
  }

  public EngineInstance instance(String name) {
    return requireInstance(name);
  }

  public List<EngineInstance> instances() {
    return store.listInstances();
  }

  public void deleteInstance(String name) {
    store.deleteInstance(name);
  }

  public PolicyEntity createPolicy(String instanceName, PolicyEntity policy) {
    EngineInstance instance = requireInstance(instanceName);
    validator.validatePolicy(instance, policy, enabledOthers(instanceName, null));
    return store.createPolicy(instanceName, policy);
  }

  public PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy) {
    EngineInstance instance = requireInstance(instanceName);
    validator.validatePolicy(instance, policy, enabledOthers(instanceName, policyName));
    return store.updatePolicy(instanceName, policyName, policy);
  }

  public void deletePolicy(String instanceName, String policyName) {
    requireInstance(instanceName);
    store.deletePolicy(instanceName, policyName);
  }

  public List<PolicyEntity> policies(String instanceName) {
    return store.listPolicies(instanceName);
  }

  public EffectiveConfigResponse effective(String name) {
    EngineInstance instance = requireInstance(name);
    EffectiveConfigResponse compiled =
        EffectiveConfigCompiler.compile(instance, store.listPolicies(name));
    return new EffectiveConfigResponse(instance.name(), instance.dialect(),
        store.currentVersion(name), compiled.policySummary(), compiled.config());
  }

  /**
   * Guard for metadata updates: enabled policies may not be left pointing at
   * tables or columns that no longer exist (disabled ones may dangle — they
   * are re-validated if ever re-enabled).
   */
  private void ensureEnabledPoliciesResolve(String instanceName, EngineInstance updated) {
    List<String> dangling = new ArrayList<>();
    for (PolicyEntity policy : store.listPolicies(instanceName)) {
      if (policy.enabled() && !resolves(updated, policy)) {
        dangling.add(policy.name());
      }
    }
    if (!dangling.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot update tables of instance '" + instanceName + "': enabled policies "
              + dangling + " reference tables/columns that no longer exist");
    }
  }

  private static boolean resolves(EngineInstance instance, PolicyEntity policy) {
    ResourceSelector resource = policy.resource();
    TableDef target = instance.tables().stream()
        .filter(t -> ColumnKey.normalize(t.catalog(), "catalog")
            .equals(ColumnKey.normalize(resource.catalog(), "catalog"))
            && ColumnKey.normalize(t.schema(), "schema")
                .equals(ColumnKey.normalize(resource.schema(), "schema"))
            && ColumnKey.normalize(t.name(), "table")
                .equals(ColumnKey.normalize(resource.table(), "table")))
        .findFirst()
        .orElse(null);
    if (target == null || policy.policyType() != PolicyType.DATAMASK) {
      return target != null;
    }
    Set<String> columns = new HashSet<>();
    target.columns().forEach(c -> columns.add(ColumnKey.normalize(c.name(), "column")));
    return resource.columns().stream()
        .allMatch(c -> columns.contains(ColumnKey.normalize(c, "column")));
  }

  private EngineInstance requireInstance(String name) {
    return store.findInstance(name)
        .orElseThrow(() -> new SqlMaskException(
            SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
            "instance '" + name + "' not found"));
  }

  private List<PolicyEntity> enabledOthers(String instanceName, String excludedPolicyName) {
    List<PolicyEntity> others = new ArrayList<>();
    for (PolicyEntity policy : store.listPolicies(instanceName)) {
      if (policy.enabled() && !policy.name().equals(excludedPolicyName)) {
        others.add(policy);
      }
    }
    return others;
  }
}
