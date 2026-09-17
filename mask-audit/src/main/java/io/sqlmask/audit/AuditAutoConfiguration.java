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
 * Noop recorder. Nothing here connects eagerly and nothing here can fail
 * application startup.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  RestClient auditRestClient(AuditProperties properties) {
    return RestClient.builder(
            org.apache.http.HttpHost.create(properties.getElasticsearch().getUrl()))
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
        .build();
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
