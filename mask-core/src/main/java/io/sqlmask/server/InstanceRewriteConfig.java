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

  @Bean
  public MetadataClient instanceMetadataClient(Upstreams props) {
    return new MetadataClient(props.metadataService().baseUrl(), props.metadataService().apiKey());
  }

  @Bean
  public PolicySourceProvider policySourceProvider(Upstreams props) {
    return new PolicySourceProvider(props.policyService().baseUrl(), props.policyService().apiKey());
  }

  /**
   * Validates one upstream's presence at the call path: the beans below are
   * built eagerly for every deployment (including ones that never serve the
   * instance-scoped endpoint), so a missing {@code base-url} must not fail
   * their construction — the endpoint rejects the request instead.
   */
  static void require(Upstreams.Service service, String key) {
    if (service == null || service.baseUrl() == null || service.baseUrl().isBlank()) {
      throw new IllegalStateException(
          key + " must be configured for the instance-scoped rewrite endpoint");
    }
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
