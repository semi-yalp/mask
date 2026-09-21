package io.sqlmask.policyserver.store;

import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam of the policy service: instances, their connections, policies
 * and their immutable version history, UDF registry, and a per-instance
 * {@code config_version} that must advance on every mutation so engines can
 * cache the compiled effective config.
 *
 * <p>Version semantics: every {@code createPolicy}/{@code updatePolicy}/
 * {@code rollbackPolicy} appends an immutable {@link PolicyVersion} row and
 * advances the policy's {@code currentVersion}. {@code updatePolicy} is
 * optimistic: the incoming {@code PolicyEntity} must carry the stored version,
 * otherwise {@code CONCURRENT_MODIFICATION}. {@code rollbackPolicy}
 * materializes earlier content as a <em>new</em> version (never a pointer
 * move); an unknown target version is {@code VERSION_NOT_FOUND}.
 *
 * <p>Error contract: duplicate creations, unknown policies/UDFs and guarded
 * deletes throw {@code SqlMaskException(CONFIG_ERROR)}; any operation naming
 * an unknown instance (except {@link #findInstance}) throws
 * {@code SqlMaskException(POLICY_INSTANCE_NOT_FOUND)}.
 */
public interface PolicyStore {

  EngineInstance createInstance(EngineInstance instance);

  EngineInstance updateInstanceConnection(String name, ConnectionConfig cfg, ConnectionStatus status);

  EngineInstance updateInstanceTables(String name, List<TableDef> tables);

  Optional<EngineInstance> findInstance(String name);

  List<EngineInstance> listInstances();

  /** Deletes the instance; existing policies make this a {@code CONFIG_ERROR}. */
  void deleteInstance(String name);

  /** Adds a policy, writes its version-1 history row and bumps the instance version. */
  PolicyEntity createPolicy(String instanceName, PolicyEntity policy);

  /**
   * Replaces the policy stored under {@code policyName} as a new version.
   * Optimistic: {@code policy.currentVersion()} must equal the stored value,
   * otherwise {@code CONCURRENT_MODIFICATION}.
   */
  PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy);

  /**
   * Rolls the policy back: materialize history version {@code targetVersion}'s
   * content as a new version (change type ROLLBACK). Unknown policy is a
   * {@code CONFIG_ERROR}; unknown target version is {@code VERSION_NOT_FOUND}.
   */
  PolicyEntity rollbackPolicy(String instanceName, String policyName, int targetVersion);

  Optional<PolicyEntity> findPolicy(String instanceName, String policyName);

  List<PolicyEntity> listPolicies(String instanceName);

  /** Immutable history, ascending by version. */
  List<PolicyVersion> policyVersions(String instanceName, String policyName);

  /** Removes the current policy; version history is retained. */
  void deletePolicy(String instanceName, String policyName);

  UdfDefinition createUdf(String instanceName, UdfDefinition udf);

  UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf);

  Optional<UdfDefinition> findUdf(String instanceName, String udfName);

  List<UdfDefinition> listUdfs(String instanceName);

  void deleteUdf(String instanceName, String udfName);

  /** Config mutation counter, starting at 1; unknown instance throws {@code POLICY_INSTANCE_NOT_FOUND}. */
  long currentVersion(String instanceName);
}