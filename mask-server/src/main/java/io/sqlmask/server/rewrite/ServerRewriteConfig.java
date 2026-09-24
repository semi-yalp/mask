package io.sqlmask.server.rewrite;

import io.sqlmask.common.metadata.MetadataClient;
import io.sqlmask.common.metrics.EffectiveMetrics;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.server.InstanceRewriteConfig.PolicySourceProvider;
import io.sqlmask.introspect.PgMetadataIntrospector;
import io.sqlmask.rewrite.RewriteEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The monolith's rewrite-path wiring: local (in-process) transports are the
 * primary beans; the HTTP transports registered by {@link io.sqlmask.server.InstanceRewriteConfig}
 * remain available for deployments that point at standalone services.
 */
@Configuration
public class ServerRewriteConfig {

  @Bean
  RewriteEngine rewriteEngine() {
    return new RewriteEngine();
  }

  @Bean
  PgMetadataIntrospector pgMetadataIntrospector() {
    return new PgMetadataIntrospector();
  }

  @Bean
  @Primary
  MetadataClient localMetadataClient(MetadataService metadata) {
    return new LocalMetadataClient(metadata);
  }

  @Bean
  @Primary
  PolicySourceProvider localPolicySourceProvider(PolicyService policies, EffectiveMetrics metrics) {
    return new PolicySourceProvider() {
      private final java.util.concurrent.ConcurrentHashMap<String, LocalPolicyConfigSource> sources =
          new java.util.concurrent.ConcurrentHashMap<>();

      @Override
      public io.sqlmask.config.source.ConfigSource forInstance(String name) {
        return sources.computeIfAbsent(name,
            n -> new LocalPolicyConfigSource(policies, n, metrics));
      }
    };
  }

  @Bean
  RewriteContextRepository rewriteContextRepository(MetadataClient metadata,
                                                    PolicySourceProvider policies) {
    return new RewriteContextRepository(metadata, policies);
  }
}
