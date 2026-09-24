package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Wires the audit pipeline (spec §2/§4): explicit {@code audit.enabled=true}
 * builds a shared ES rest client (+ Java client) from
 * {@code audit.elasticsearch.*}, starts the background writer and exposes
 * the search client; otherwise (the Java default {@code enabled=false}) it
 * falls back to a Noop recorder. Nothing connects eagerly; with the
 * pipeline enabled, configuration errors (bad URL, conflicting credentials)
 * fail startup on purpose — auditing that silently never reaches ES is
 * worse than a refused boot.
 *
 * <p>Additionally, a non-blank {@code risk.forward.url} wraps whichever recorder
 * is active into a {@link ForwardingAuditRecorder} ({@code @Primary}) that also
 * ships every event to the risk monitoring service - best-effort and fully
 * inert when the property is unset.
 */
@AutoConfiguration
@EnableConfigurationProperties({AuditProperties.class, RiskForwardProperties.class})
public class AuditAutoConfiguration {

  /**
   * The pipeline builds only when {@code audit.enabled} is explicitly true:
   * the Java default ({@link AuditProperties#enabled}=false) is the single
   * source of the off state, so a bare jar with no audit config runs the
   * Noop recorder instead of pointing an ES pipeline at localhost.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true")
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
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true")
  ElasticsearchClient auditElasticsearchClient(RestClient auditRestClient) {
    return new ElasticsearchClient(
        new RestClientTransport(auditRestClient, new JacksonJsonpMapper(new ObjectMapper())));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true")
  EsAuditRecorder esAuditRecorder(ElasticsearchClient auditElasticsearchClient,
      AuditProperties properties, MeterRegistry meterRegistry) {
    return new EsAuditRecorder(auditElasticsearchClient, properties, meterRegistry);
  }

  @Bean
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true")
  AuditSearchClient auditSearchClient(ElasticsearchClient auditElasticsearchClient,
      AuditProperties properties) {
    return new AuditSearchClient(auditElasticsearchClient, properties.getIndexPrefix());
  }

  /**
   * Async risk forwarder, registered only for a non-blank ingest URL.
   * Declared before the Noop fallback so the Noop bean's missing-bean
   * condition can see the forwarding decorator and back off.
   */
  @Bean(destroyMethod = "close")
  @Conditional(AuditAutoConfiguration.RiskForwardEnabled.class)
  RiskForwarder riskForwarder(RiskForwardProperties properties,
      ObjectProvider<MeterRegistry> registries) {
    return new RiskForwarder(properties, registries.getIfAvailable());
  }

  /**
   * @Primary decorator over the active recorder when forwarding is on. Binds
   * the delegate by its concrete type (never {@code AuditRecorder}) so the
   * decoration cannot resolve back into itself while it is being created.
   */
  @Bean
  @Primary
  @Conditional(AuditAutoConfiguration.RiskForwardEnabled.class)
  ForwardingAuditRecorder forwardingAuditRecorder(
      @org.springframework.lang.Nullable EsAuditRecorder esAuditRecorder,
      RiskForwarder forwarder) {
    AuditRecorder delegate = esAuditRecorder != null ? esAuditRecorder : new NoopAuditRecorder();
    return new ForwardingAuditRecorder(delegate, forwarder);
  }

  /** Non-blank {@code risk.forward.url} enables the forwarding path. */
  static class RiskForwardEnabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String url = context.getEnvironment().getProperty("risk.forward.url");
      return url != null && !url.isBlank();
    }
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
