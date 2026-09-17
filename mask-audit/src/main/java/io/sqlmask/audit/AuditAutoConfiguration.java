package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the audit pipeline (spec §2/§4/§6): enabled (default) builds one
 * shared {@link ElasticsearchClient} used by both the background writer
 * ({@link EsAuditRecorder}) and the search client ({@link AuditSearchClient});
 * disabled falls back to a Noop recorder. Nothing here connects eagerly and
 * nothing here can fail application startup.
 *
 * <p>Destroy order is explicit because the transport has no owner of its own:
 * Spring destroys dependents before dependencies, so recorder {@code close}
 * (queue drain) runs first, then the transport closes the REST client, then
 * the REST client itself closes (idempotent double close is harmless).</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  RestClient auditRestClient(AuditProperties properties) {
    return RestClient.builder(HttpHost.create(properties.getElasticsearch().getUrl()))
        .setHttpClientConfigCallback(builder -> {
          if (!properties.getElasticsearch().getApiKey().isBlank()) {
            builder.setDefaultHeaders(List.of(new org.apache.http.message.BasicHeader(
                "Authorization", "ApiKey " + properties.getElasticsearch().getApiKey())));
          }
          if (!properties.getElasticsearch().getUsername().isBlank()) {
            org.apache.http.impl.client.BasicCredentialsProvider provider =
                new org.apache.http.impl.client.BasicCredentialsProvider();
            provider.setCredentials(org.apache.http.auth.AuthScope.ANY,
                new org.apache.http.auth.UsernamePasswordCredentials(
                    properties.getElasticsearch().getUsername(),
                    properties.getElasticsearch().getPassword()));
            builder.setDefaultCredentialsProvider(provider);
          }
          return builder;
        })
        // spec §4.1: a bulk must not hang the writer (or the query endpoint)
        // on a dead ES for the ~30s httpclient default.
        .setRequestConfigCallback(AuditAutoConfiguration::esTimeouts)
        .build();
  }

  /** Bulk HTTP timeouts (spec §4.1): connect 3s / socket 10s. Package-private for tests. */
  static org.apache.http.client.config.RequestConfig.Builder esTimeouts(
      org.apache.http.client.config.RequestConfig.Builder requestConfig) {
    return requestConfig.setConnectTimeout(3_000).setSocketTimeout(10_000);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  ElasticsearchClient auditElasticsearchClient(RestClient auditRestClient) {
    return new CloseableElasticsearchClient(
        new RestClientTransport(auditRestClient, new JacksonJsonpMapper(new ObjectMapper())));
  }

  /**
   * {@code ElasticsearchClient} itself has no {@code close()} in
   * elasticsearch-java 8.13, but Spring's {@code destroyMethod = "close"}
   * requires one on the runtime class. This subclass closes the shared
   * transport (which closes the underlying REST client), keeping the explicit
   * destroy chain: recorder drain first, then transport, then REST client.
   */
  static final class CloseableElasticsearchClient extends ElasticsearchClient
      implements AutoCloseable {

    private final ElasticsearchTransport transport;

    CloseableElasticsearchClient(ElasticsearchTransport transport) {
      super(transport);
      this.transport = transport;
    }

    @Override
    public void close() {
      try {
        transport.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  EsAuditRecorder esAuditRecorder(ElasticsearchClient client, AuditProperties properties) {
    return new EsAuditRecorder(client, properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  AuditSearchClient auditSearchClient(ElasticsearchClient client, AuditProperties properties) {
    return new AuditSearchClient(client, properties.getIndexPrefix());
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
