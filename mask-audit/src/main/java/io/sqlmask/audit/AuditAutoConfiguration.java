package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the audit pipeline (spec §2/§4): enabled (default) builds a shared ES
 * rest client (+ Java client) from {@code audit.elasticsearch.*}, starts the
 * background writer and exposes the search client; disabled falls back to a
 * Noop recorder. Nothing connects eagerly; configuration errors (bad URL,
 * conflicting credentials) fail startup on purpose — auditing that silently
 * never reaches ES is worse than a refused boot.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  RestClient auditRestClient(AuditProperties properties) {
    org.apache.http.HttpHost host;
    try {
      host = org.apache.http.HttpHost.create(properties.getElasticsearch().getUrl());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("audit.elasticsearch.url is invalid: '"
          + properties.getElasticsearch().getUrl() + "'", e);
    }
    if (!properties.getElasticsearch().getApiKey().isBlank()
        && !properties.getElasticsearch().getUsername().isBlank()) {
      throw new IllegalStateException("audit.elasticsearch: apiKey and username/password "
          + "are mutually exclusive; configure one credential mechanism");
    }
    return RestClient.builder(host)
        .setHttpClientConfigCallback(builder -> {
          if (!properties.getElasticsearch().getApiKey().isBlank()) {
            builder.setDefaultHeaders(java.util.Collections.singletonList(
                new org.apache.http.message.BasicHeader("Authorization",
                    "ApiKey " + properties.getElasticsearch().getApiKey())));
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
        .setRequestConfigCallback(AuditAutoConfiguration::esTimeouts)
        .build();
  }

  /**
   * Fail-fast ES timeouts (spec §4.1: connect 3s / socket 10s) so a hung
   * cluster does not stall the writer thread or the query API for the ~30s
   * default socket timeout.
   */
  static org.apache.http.client.config.RequestConfig.Builder esTimeouts(
      org.apache.http.client.config.RequestConfig.Builder rc) {
    return rc.setConnectTimeout(3_000).setSocketTimeout(10_000);
  }

  @Bean
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  ElasticsearchClient auditElasticsearchClient(RestClient auditRestClient) {
    return new ElasticsearchClient(
        new RestClientTransport(auditRestClient, new JacksonJsonpMapper(new ObjectMapper())));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  EsAuditRecorder esAuditRecorder(ElasticsearchClient auditElasticsearchClient,
      AuditProperties properties, MeterRegistry meterRegistry) {
    return new EsAuditRecorder(auditElasticsearchClient, properties, meterRegistry);
  }

  @Bean
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  AuditSearchClient auditSearchClient(ElasticsearchClient auditElasticsearchClient,
      AuditProperties properties) {
    return new AuditSearchClient(auditElasticsearchClient, properties.getIndexPrefix());
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
