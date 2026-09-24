package io.sqlmask.server.rewrite;

import io.sqlmask.common.metadata.MetadataClient;
import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.config.source.InstanceQueryAssembler;
import io.sqlmask.config.LoadedConfig;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.server.InstanceRewriteConfig.PolicySourceProvider;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rewrite hot path's one-stop compiled-config source: per (instance,
 * subject) it pairs the metadata snapshot with the subject's effective
 * policies into the {@link LoadedConfig} the kernel consumes — entirely
 * in-process, zero HTTP, zero poll interval.
 *
 * <p>Isolation guarantee ("metadata and policy must not affect rewrite"):
 * the metadata snapshot is cached per instance and only re-fetched when the
 * metadata version moves; the effective config is served from the local
 * subject LRU stamped with the policy store's config version. A metadata or
 * policy failure therefore surfaces as a structured error on the request
 * that touched it, never as latency or unavailability of the rewrite
 * pipeline itself; unchanged instances keep serving their cached snapshot.
 */
public final class RewriteContextRepository {

  /** Kernel input plus the dialect the snapshot dictates. */
  public record Context(LoadedConfig config, String dialect) {
  }

  private final MetadataClient metadata;
  private final PolicySourceProvider policies;
  private final InstanceQueryAssembler assembler = new InstanceQueryAssembler();
  private final Map<String, MetadataClient.MetadataSnapshot> snapshots =
      new ConcurrentHashMap<>();

  public RewriteContextRepository(MetadataClient metadata, PolicySourceProvider policies) {
    this.metadata = metadata;
    this.policies = policies;
  }

  /** Assembles the kernel input for one instance and subject. */
  public Context load(String instance, Subject subject) {
    // metadata first: an unknown instance surfaces as METADATA_INSTANCE_NOT_FOUND,
    // matching the standalone service's error precedence
    MetadataClient.MetadataSnapshot snapshot = snapshotOf(instance);
    ConfigSource.ResolvedConfig effective = policies.forInstance(instance).load(subject);
    return new Context(assembler.assemble(snapshot, effective), snapshot.dialect());
  }

  /** Snapshot cache with version check: a cheap version probe decides between
   * serving the cached structure and re-fetching it. */
  private MetadataClient.MetadataSnapshot snapshotOf(String instance) {
    MetadataClient.MetadataSnapshot cached = snapshots.get(instance);
    if (cached != null) {
      OptionalLong current = metadata.versionOf(instance);
      if (current.isPresent() && current.getAsLong() == cached.metadataVersion()) {
        return cached;
      }
    }
    MetadataClient.MetadataSnapshot fresh = metadata.fetch(instance);
    snapshots.put(instance, fresh);
    return fresh;
  }

  /** Drops cached state (one instance, or everything when null/blank). */
  public int invalidate(String instance) {
    if (instance == null || instance.isBlank()) {
      int n = snapshots.size();
      snapshots.clear();
      return n;
    }
    return snapshots.remove(instance) != null ? 1 : 0;
  }

  /** Factory for the local (monolith) repository wiring. */
  public static RewriteContextRepository local(MetadataService metadataService,
                                               PolicyService policyService,
                                               io.sqlmask.common.metrics.EffectiveMetrics metrics) {
    return new RewriteContextRepository(
        new LocalMetadataClient(metadataService),
        new PolicySourceProvider() {
          private final ConcurrentHashMap<String, LocalPolicyConfigSource> sources =
              new ConcurrentHashMap<>();

          @Override
          public ConfigSource forInstance(String name) {
            return sources.computeIfAbsent(name,
                n -> new LocalPolicyConfigSource(policyService, n, metrics));
          }
        });
  }
}
