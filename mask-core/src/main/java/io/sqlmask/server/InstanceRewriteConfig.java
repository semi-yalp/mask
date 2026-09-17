package io.sqlmask.server;

import io.sqlmask.config.source.PolicyServiceConfigSource;
import io.sqlmask.metadataclient.MetadataClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

/** Service-mode wiring for the instance-scoped rewrite endpoint. */
@Configuration
@EnableConfigurationProperties(InstanceRewriteConfig.Upstreams.class)
public class InstanceRewriteConfig {

  @ConfigurationProperties(prefix = "sqlmask")
  public record Upstreams(Service metadataService, Service policyService) {
    public record Service(String baseUrl, String apiKey) {}
  }

  /** Null when service mode is unconfigured: the instance-scoped endpoint
   * then rejects requests at the call path with {@code CONFIG_ERROR} instead
   * of failing context startup (the same jar also serves the inline rewrite
   * mode, which needs no upstreams). */
  private static Upstreams.Service configured(Upstreams.Service service) {
    return service == null || service.baseUrl() == null || service.baseUrl().isBlank()
        ? null
        : service;
  }

  @Bean
  public MetadataClient instanceMetadataClient(Upstreams props) {
    Upstreams.Service service = configured(props.metadataService());
    return service == null ? null : new MetadataClient(service.baseUrl(), service.apiKey());
  }

  @Bean
  public PolicySourceProvider policySourceProvider(Upstreams props) {
    Upstreams.Service service = configured(props.policyService());
    return service == null ? null : new PolicySourceProvider(service.baseUrl(), service.apiKey());
  }

  /** One cached PolicyServiceConfigSource per instance name (per-subject LRU lives inside). */
  public static final class PolicySourceProvider {
    private final String baseUrl;
    private final String apiKey;
    private final ConcurrentHashMap<String, PolicyServiceConfigSource> sources = new ConcurrentHashMap<>();

    public PolicySourceProvider(String baseUrl, String apiKey) {
      this.baseUrl = baseUrl;
      this.apiKey = apiKey;
    }

    public PolicyServiceConfigSource forInstance(String name) {
      return sources.computeIfAbsent(name, n -> new PolicyServiceConfigSource(baseUrl, apiKey, n));
    }
  }
}
