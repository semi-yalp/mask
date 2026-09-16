package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the audit pipeline (spec §2/§4): enabled (default) builds the ES
 * client from {@code audit.elasticsearch.*} and starts the background writer;
 * disabled falls back to a Noop recorder. Nothing here connects eagerly and
 * nothing here can fail application startup.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  EsAuditRecorder esAuditRecorder(AuditProperties properties) {
    RestClient rest = RestClient.builder(HttpHost.create(properties.getElasticsearch().getUrl()))
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
        .build();
    ElasticsearchClient client = new ElasticsearchClient(
        new RestClientTransport(rest, new JacksonJsonpMapper(new ObjectMapper())));
    return new EsAuditRecorder(client, properties);
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
