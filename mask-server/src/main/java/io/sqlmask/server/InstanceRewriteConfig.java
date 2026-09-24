package io.sqlmask.server;

import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.config.source.PolicyServiceConfigSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Service-mode wiring for the instance-scoped rewrite path. Two transports
 * exist: the local provider (in-process domain services, the monolith default,
 * registered by {@code ServerRewriteConfig}) and the HTTP provider (standalone
 * deployments / CLI runs pointed at a remote policy service via
 * {@code sqlmask.policy-service.base-url}).
 */
@Configuration
@EnableConfigurationProperties(InstanceRewriteConfig.Upstreams.class)
public class InstanceRewriteConfig {

  @ConfigurationProperties(prefix = "sqlmask")
  public record Upstreams(Service metadataService, Service policyService) {
    public record Service(String baseUrl, String apiKey) {}
  }

  /** Null when the service is unconfigured: the instance-scoped endpoint
   * then rejects requests at the call path with {@code CONFIG_ERROR} instead
   * of failing context startup (the same jar also serves the inline rewrite
   * mode, which needs no upstreams). */
  static Upstreams.Service configured(Upstreams.Service service) {
    return service == null || service.baseUrl() == null || service.baseUrl().isBlank()
        ? null
        : service;
  }

  /** HTTP metadata transport for deployments that run metadata standalone. */
  @Bean(name = "httpMetadataClient")
  public io.sqlmask.common.metadata.MetadataClient httpMetadataClient(Upstreams props) {
    Upstreams.Service service = configured(props.metadataService());
    return service == null ? null : new io.sqlmask.common.metadata.HttpMetadataClient(
        service.baseUrl(), service.apiKey());
  }

  /** HTTP policy transport: one cached source per instance name. */
  @Bean(name = "httpPolicySourceProvider")
  public PolicySourceProvider httpPolicySourceProvider(Upstreams props) {
    Upstreams.Service service = configured(props.policyService());
    return service == null ? null : PolicySourceProvider.http(service.baseUrl(), service.apiKey());
  }

  /** Supplies the subject-resolved effective config for one instance. */
  public interface PolicySourceProvider {
    ConfigSource forInstance(String name);

    /** One cached {@link PolicyServiceConfigSource} per instance name
     * (per-subject LRU lives inside). */
    static PolicySourceProvider http(String baseUrl, String apiKey) {
      return new PolicySourceProvider() {
        private final ConcurrentHashMap<String, PolicyServiceConfigSource> sources =
            new ConcurrentHashMap<>();

        @Override
        public ConfigSource forInstance(String name) {
          return sources.computeIfAbsent(name,
              n -> new PolicyServiceConfigSource(baseUrl, apiKey, n));
        }
      };
    }
  }
}
