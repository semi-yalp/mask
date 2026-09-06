package io.sqlmask.policyserver.store;

import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.TableDef;

import java.util.List;
import java.util.Optional;

/**
 * Storage seam of the policy service: instances, their policies and a
 * per-instance {@code config_version} that must advance on every mutation so
 * engines can cache the compiled effective config.
 *
 * <p>Error contract: duplicate creations and invalid deletes throw
 * {@code SqlMaskException(CONFIG_ERROR)}; any operation naming an unknown
 * instance (except {@link #findInstance}) throws
 * {@code SqlMaskException(POLICY_INSTANCE_NOT_FOUND)}.
 */
public interface PolicyStore {

  /** Registers a new instance; a duplicate name is a {@code CONFIG_ERROR}. */
  EngineInstance createInstance(EngineInstance instance);

  /** Replaces the table list of an existing instance, keeping name and dialect. */
  EngineInstance updateInstanceTables(String name, List<TableDef> tables);

  /** Lookup primitive; empty for an unknown name. */
  Optional<EngineInstance> findInstance(String name);

  /** All instances in declaration order. */
  List<EngineInstance> listInstances();

  /** Deletes the instance; existing policies make this a {@code CONFIG_ERROR}. */
  void deleteInstance(String name);

  /** Adds a policy; a duplicate name within the instance is a {@code CONFIG_ERROR}. */
  PolicyEntity createPolicy(String instanceName, PolicyEntity policy);

  /** Replaces the policy stored under {@code policyName} (enablement may change). */
  PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy);

  /** Looks up one policy; empty for an unknown name. */
  Optional<PolicyEntity> findPolicy(String instanceName, String policyName);

  /** All policies of the instance in creation order. */
  List<PolicyEntity> listPolicies(String instanceName);

  void deletePolicy(String instanceName, String policyName);

  /** Mutation counter, starting at 1; unknown instance throws {@code POLICY_INSTANCE_NOT_FOUND}. */
  long currentVersion(String instanceName);
}
