package io.sqlmask.policyserver;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.compile.EffectiveConfigCompiler;
import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.policyserver.store.PolicyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Write-path facade over {@link PolicyStore}: every mutation validates first
 * (via {@link PolicyValidator}) so the store only ever holds self-consistent
 * state; {@link #effective} compiles the stored state into the wire contract
 * stamped with the store's current {@code config_version}. Connection-aware
 * operations funnel through {@link EngineAccess}: instance creation is
 * fail-closed on the connection test (a failed test persists nothing).
 */
public class PolicyService {

  private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

  private final PolicyStore store;
  private final PolicyValidator validator;

  public PolicyService(PolicyStore store, PolicyValidator validator) {
    this.store = store;
    this.validator = validator;
  }

  /**
   * Creates an instance. With a {@code connection} present the engine is
   * tested first (fail ⇒ {@code CONNECTION_FAILED}, nothing persisted) and the
   * instance is {@code CONNECTED}; when {@code fetchMetadata} is true the live
   * table structure is captured into the snapshot. Without a connection the
   * instance is created {@code UNCONNECTED} (metadata-only, populated later via
   * {@code updateInstanceTables}).
   */
  public EngineInstance createInstance(String name, String dialect, ConnectionConfig connection,
      boolean fetchMetadata, EngineAccess access) {
    ConnectionStatus status = ConnectionStatus.UNCONNECTED;
    List<TableDef> tables = List.of();
    if (connection != null) {
      access.test(connection);
      status = ConnectionStatus.CONNECTED;
      if (fetchMetadata) {
        tables = access.fetch(connection);
      }
    }
    EngineInstance instance = new EngineInstance(name, dialect, connection, status, tables);
    validator.validateInstance(instance);
    return store.createInstance(instance);
  }

  /** Replaces the connection, re-tests, and records the resulting status. */
  public EngineInstance updateConnection(String name, ConnectionConfig connection,
      EngineAccess access) {
    requireInstance(name);
    access.test(connection);
    return store.updateInstanceConnection(name, connection, ConnectionStatus.CONNECTED);
  }

  /** Re-tests the stored connection and records the resulting status. */
  public ConnectionStatus retestConnection(String name, EngineAccess access) {
    EngineInstance current = requireInstance(name);
    if (current.connection() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "' has no connection to test");
    }
    access.test(current.connection());
    store.updateInstanceConnection(name, current.connection(), ConnectionStatus.CONNECTED);
    return ConnectionStatus.CONNECTED;
  }

  /** Pulls live table structure from the engine and replaces the snapshot. */
  public EngineInstance metadataFetch(String name, EngineAccess access) {
    EngineInstance current = requireInstance(name);
    if (current.connection() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "' has no connection to fetch metadata from");
    }
    List<TableDef> tables = access.fetch(current.connection());
    EngineInstance updated = new EngineInstance(name, current.dialect(), current.connection(),
        ConnectionStatus.CONNECTED, tables);
    validator.validateInstance(updated);
    ensureEnabledPoliciesResolve(updated);
    return store.updateInstanceTables(name, tables);
  }

  public EngineInstance instance(String name) {
    return requireInstance(name);
  }

  public List<EngineInstance> instances() {
    return store.listInstances();
  }

  /** Replaces the table snapshot (manual metadata entry); enabled policies must keep resolving. */
  public EngineInstance replaceTables(String name, List<TableDef> tables) {
    EngineInstance current = requireInstance(name);
    EngineInstance updated = new EngineInstance(name, current.dialect(), current.connection(),
        current.status(), tables);
    validator.validateInstance(updated);
    ensureEnabledPoliciesResolve(updated);
    return store.updateInstanceTables(name, tables);
  }

  public long configVersion(String instanceName) {
    return store.currentVersion(instanceName);
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

  public PolicyEntity rollbackPolicy(String instanceName, String policyName, int targetVersion) {
    requireInstance(instanceName);
    return store.rollbackPolicy(instanceName, policyName, targetVersion);
  }

  public List<PolicyVersion> policyVersions(String instanceName, String policyName) {
    requireInstance(instanceName);
    return store.policyVersions(instanceName, policyName);
  }

  public Optional<PolicyEntity> policy(String instanceName, String policyName) {
    requireInstance(instanceName);
    return store.findPolicy(instanceName, policyName);
  }

  public List<PolicyEntity> policies(String instanceName) {
    return store.listPolicies(instanceName);
  }

  public void deletePolicy(String instanceName, String policyName) {
    requireInstance(instanceName);
    store.deletePolicy(instanceName, policyName);
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
    ensureEnabledPoliciesResolveUdfs(instanceName,
        store.listUdfs(instanceName).stream()
            .map(u -> u.name().equals(udfName) ? udf : u).toList());
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
    List<String> warnings = new ArrayList<>();
    EffectiveConfigResponse compiled = EffectiveConfigCompiler.compile(instance,
        store.listPolicies(name), subject, warnings);
    if (!warnings.isEmpty()) {
      log.info("instance '{}' effective config warnings: {}", name, warnings);
    }
    return new EffectiveConfigResponse(instance.name(), instance.dialect(),
        configVersion, compiled.policySummary(), compiled.config());
  }

  private void ensureEnabledPoliciesResolve(EngineInstance updated) {
    List<String> dangling = new ArrayList<>();
    for (PolicyEntity policy : store.listPolicies(updated.name())) {
      if (policy.enabled() && !resolves(updated, policy)) {
        dangling.add(policy.name());
      }
    }
    if (!dangling.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot update tables of instance '" + updated.name() + "': enabled policies "
              + dangling + " reference tables/columns that no longer exist");
    }
  }

  private void ensureEnabledPoliciesResolveUdfs(String instanceName, List<UdfDefinition> udfsAfter) {
    List<String> dangling = validator.policiesFailingUdfResolution(
        requireInstance(instanceName), udfsAfter, store.listPolicies(instanceName));
    if (!dangling.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot change udfs of instance '" + instanceName + "': enabled policies " + dangling
              + " reference udf signatures that no longer resolve; disable them first");
    }
  }

  private static boolean resolves(EngineInstance instance, PolicyEntity policy) {
    ResourceSelector resource = policy.resource();
    TableDef target = instance.tables().stream()
        .filter(t -> eq(t.catalog(), resource.catalog())
            && eq(t.schema(), resource.schema())
            && eq(t.name(), resource.table()))
        .findFirst()
        .orElse(null);
    if (target == null || policy.policyType() != PolicyType.DATAMASK) {
      return target != null;
    }
    var columns = target.columns().stream()
        .map(c -> c.name().toLowerCase(java.util.Locale.ROOT)).toList();
    return policy.resource().columns().stream()
        .allMatch(c -> columns.contains(c.toLowerCase(java.util.Locale.ROOT)));
  }

  private static boolean eq(String a, String b) {
    return (a == null ? "" : a).toLowerCase(java.util.Locale.ROOT)
        .equals((b == null ? "" : b).toLowerCase(java.util.Locale.ROOT));
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