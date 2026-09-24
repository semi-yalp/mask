package io.sqlmask.policyserver;

import io.sqlmask.common.effective.EffectiveConfigResponse;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.store.PolicyYamlLoader;
import io.sqlmask.policyserver.compile.EffectiveConfigCompiler;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.policyserver.store.PolicyStore;
import io.sqlmask.policyserver.transfer.PolicyImportMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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
    validator.validatePolicy(instance, store.listUdfs(instanceName), policy,
        enabledOthers(instanceName, null));
    return store.createPolicy(instanceName, policy);
  }

  public PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy) {
    EngineInstance instance = requireInstance(instanceName);
    validator.validatePolicy(instance, store.listUdfs(instanceName), policy,
        enabledOthers(instanceName, policyName));
    return store.updatePolicy(instanceName, policyName, policy);
  }

  public void deletePolicy(String instanceName, String policyName) {
    requireInstance(instanceName);
    store.deletePolicy(instanceName, policyName);
  }

  public List<PolicyEntity> policies(String instanceName) {
    return store.listPolicies(instanceName);
  }

  /** Outcome of a policies.yaml import: how many policies were created vs updated. */
  public record ImportResult(int created, int updated) {
  }

  private final PolicyImportMapper importMapper = new PolicyImportMapper();

  /**
   * Imports a Ranger-style policies.yaml document into the instance. The
   * whole file is validated before anything is written — structural errors
   * surface with their YAML path (e.g. {@code policies.yaml:
   * policies[0].dataMaskItems[1]}), and semantic validation simulates the
   * sequential create/update state, so an invalid policy rejects the file
   * without touching the instance. Existing policies are upserted by name.
   * The apply loop writes policies one by one (each bumps config_version);
   * it is not a single transaction, so a concurrent admin change could in
   * theory interleave mid-import.
   */
  public ImportResult importPolicies(String instanceName, String yaml) {
    requireInstance(instanceName);
    List<PolicyEntity> entities;
    try {
      entities = importMapper.toEntities(new PolicyYamlLoader().parse(yaml, "policies.yaml"));
    } catch (PolicyException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, e.getMessage());
    }
    validateImport(instanceName, entities);
    Set<String> existingNames = new HashSet<>();
    store.listPolicies(instanceName).forEach(p -> existingNames.add(p.name()));
    int created = 0;
    int updated = 0;
    for (PolicyEntity entity : entities) {
      if (existingNames.contains(entity.name())) {
        store.updatePolicy(instanceName, entity.name(), entity);
        updated++;
      } else {
        store.createPolicy(instanceName, entity);
        created++;
      }
    }
    return new ImportResult(created, updated);
  }

  /**
   * Validates every imported entity against the instance before any write,
   * simulating the state sequential create/update would produce: existing
   * enabled policies with names the file replaces drop out of the overlap
   * check, earlier enabled file entities join it. First failure rejects the
   * whole file.
   */
  private void validateImport(String instanceName, List<PolicyEntity> entities) {
    EngineInstance instance = requireInstance(instanceName);
    List<UdfDefinition> udfs = store.listUdfs(instanceName);
    Set<String> importedNames = new HashSet<>();
    entities.forEach(e -> importedNames.add(e.name()));
    List<PolicyEntity> simulatedEnabled = new ArrayList<>();
    for (PolicyEntity existing : store.listPolicies(instanceName)) {
      if (existing.enabled() && !importedNames.contains(existing.name())) {
        simulatedEnabled.add(existing);
      }
    }
    for (PolicyEntity entity : entities) {
      if (entity.enabled()) {
        simulatedEnabled.add(entity);
      }
      validator.validatePolicy(instance, udfs, entity, simulatedEnabled);
    }
  }

  public UdfDefinition createUdf(String instanceName, UdfDefinition udf) {
    EngineInstance instance = requireInstance(instanceName);
    validator.validateUdf(instance, udf);
    return store.createUdf(instanceName, udf);
  }

  public UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf) {
    requireInstance(instanceName);
    if (!udf.name().equals(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "udf name mismatch: '"
          + udfName + "' cannot be renamed to '" + udf.name() + "'");
    }
    validator.validateUdf(requireInstance(instanceName), udf);
    ensureEnabledPoliciesResolveUdfs(instanceName, withReplaced(store.listUdfs(instanceName), udf));
    return store.replaceUdf(instanceName, udfName, udf);
  }

  public Optional<UdfDefinition> udf(String instanceName, String udfName) {
    requireInstance(instanceName);
    return store.findUdf(instanceName, udfName);
  }

  public List<UdfDefinition> udfs(String instanceName) {
    requireInstance(instanceName);
    return store.listUdfs(instanceName);
  }

  public void deleteUdf(String instanceName, String udfName) {
    requireInstance(instanceName);
    ensureEnabledPoliciesResolveUdfs(instanceName,
        store.listUdfs(instanceName).stream()
            .filter(u -> !u.name().equals(udfName)).toList());
    store.deleteUdf(instanceName, udfName);
  }

  public EffectiveConfigResponse effective(String name, Subject subject) {
    EngineInstance instance = requireInstance(name);
    long configVersion = store.currentVersion(name);
    EffectiveConfigResponse compiled =
        EffectiveConfigCompiler.compile(instance, store.listPolicies(name), subject);
    return new EffectiveConfigResponse(instance.name(), instance.dialect(),
        configVersion, compiled.policySummary(), compiled.config());
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

  /**
   * Udf-removal guard, mirroring {@link #ensureEnabledPoliciesResolve}: enabled
   * DATAMASK policies must still resolve against the registry after the change
   * (disabled ones may dangle — they are re-validated if ever re-enabled).
   */
  private void ensureEnabledPoliciesResolveUdfs(String instanceName, List<UdfDefinition> udfsAfter) {
    List<String> dangling = validator.policiesFailingUdfResolution(
        requireInstance(instanceName), udfsAfter, store.listPolicies(instanceName));
    if (!dangling.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot change udfs of instance '" + instanceName + "': enabled policies " + dangling
              + " reference udf signatures that no longer resolve; disable them first");
    }
  }

  private static List<UdfDefinition> withReplaced(List<UdfDefinition> udfs, UdfDefinition udf) {
    return udfs.stream().map(u -> u.name().equals(udf.name()) ? udf : u).toList();
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
