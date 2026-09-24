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
 * Wires the audit pipeline: with {@code audit.enabled=true} the storage
 * backend is chosen by {@code audit.store} — {@code jdbc} (the monolith
 * default) writes to the shared SQL datasource's {@code audit_event} table,
 * {@code es} builds a shared ES rest client (+ Java client) from
 * {@code audit.elasticsearch.*}. Nothing connects eagerly; with the pipeline
 * enabled, configuration errors (bad URL, missing datasource) fail startup on
 * purpose — auditing that silently never reaches its store is worse than a
 * refused boot.
 *
 * <p>Risk forwarding: a non-blank {@code risk.forward.url} ships events to a
 * standalone risk service over HTTP ({@link RiskForwarder}); otherwise an
 * in-process {@link RiskIngestSink} bean (the monolith's risk bridge) receives
 * them. Whichever recorder is active is wrapped into a {@link ForwardingAuditRecorder}
 * ({@code @Primary}) — best-effort and fully inert when no sink exists.
 */
@AutoConfiguration
@EnableConfigurationProperties({AuditProperties.class, RiskForwardProperties.class})
public class AuditAutoConfiguration {

  /** enabled=true AND store=es: the ES-specific beans. */
  static class EsStoreEnabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      if (!"true".equals(context.getEnvironment().getProperty("audit.enabled"))) {
        return false;
      }
      String store = context.getEnvironment().getProperty("audit.store", "es");
      return !"jdbc".equalsIgnoreCase(store);
    }
  }

  /** enabled=true AND store=jdbc: the SQL-store beans. */
  static class JdbcStoreEnabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      if (!"true".equals(context.getEnvironment().getProperty("audit.enabled"))) {
        return false;
      }
      return "jdbc".equalsIgnoreCase(context.getEnvironment().getProperty("audit.store", "es"));
    }
  }

  /**
   * The pipeline builds only when {@code audit.enabled} is explicitly true:
   * the Java default ({@link AuditProperties#enabled}=false) is the single
   * source of the off state, so a bare jar with no audit config runs the
   * Noop recorder instead of pointing an ES pipeline at localhost.
   */
  @Bean(destroyMethod = "close")
  @Conditional(AuditAutoConfiguration.EsStoreEnabled.class)
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
  @Conditional(AuditAutoConfiguration.EsStoreEnabled.class)
  ElasticsearchClient auditElasticsearchClient(RestClient auditRestClient) {
    return new ElasticsearchClient(
        new RestClientTransport(auditRestClient, new JacksonJsonpMapper(new ObjectMapper())));
  }

  @Bean(destroyMethod = "close")
  @Conditional(AuditAutoConfiguration.EsStoreEnabled.class)
  EsAuditRecorder esAuditRecorder(ElasticsearchClient auditElasticsearchClient,
      AuditProperties properties, MeterRegistry meterRegistry) {
    return new EsAuditRecorder(auditElasticsearchClient, properties, meterRegistry);
  }

  @Bean
  @Conditional(AuditAutoConfiguration.EsStoreEnabled.class)
  AuditSearchClient auditSearchClient(ElasticsearchClient auditElasticsearchClient,
      AuditProperties properties) {
    return new AuditSearchClient(auditElasticsearchClient, properties.getIndexPrefix());
  }

  // ---- jdbc store ----

  /** Local template over the shared datasource: built per bean (not as a
   * container bean) so it never becomes an injection candidate for the
   * domain stores' JdbcTemplate points (which would be a cycle). */
  private static org.springframework.jdbc.core.JdbcTemplate auditJdbcTemplate(
      ObjectProvider<javax.sql.DataSource> datasources) {
    javax.sql.DataSource ds = datasources.getIfAvailable();
    if (ds == null) {
      throw new IllegalStateException("audit.store=jdbc requires a datasource "
          + "(configure MASK_STORAGE_PG_URL or run the embedded H2 fallback)");
    }
    return new org.springframework.jdbc.core.JdbcTemplate(ds);
  }

  @Bean(destroyMethod = "close")
  @Conditional(AuditAutoConfiguration.JdbcStoreEnabled.class)
  JdbcAuditRecorder jdbcAuditRecorder(ObjectProvider<javax.sql.DataSource> datasources,
      AuditProperties properties, ObjectProvider<MeterRegistry> registries) {
    return new JdbcAuditRecorder(auditJdbcTemplate(datasources), properties,
        registries.getIfAvailable());
  }

  @Bean
  @Conditional(AuditAutoConfiguration.JdbcStoreEnabled.class)
  JdbcAuditSearchClient jdbcAuditSearchClient(ObjectProvider<javax.sql.DataSource> datasources) {
    return new JdbcAuditSearchClient(auditJdbcTemplate(datasources));
  }

  // ---- risk forwarding ----

  /**
   * Async risk forwarder, registered only for a non-blank ingest URL.
   */
  @Bean(destroyMethod = "close")
  @Conditional(AuditAutoConfiguration.RiskForwardEnabled.class)
  RiskForwarder riskForwarder(RiskForwardProperties properties,
      ObjectProvider<MeterRegistry> registries) {
    return new RiskForwarder(properties, registries.getIfAvailable());
  }

  /**
   * @Primary decorator over the active recorder when any risk sink exists:
   * the HTTP forwarder (standalone risk service, {@code risk.forward.url})
   * or an in-process {@link RiskIngestSink} bean (the monolith's risk
   * bridge, visible here because user beans register before auto-configuration).
   * Binds the delegate by concrete type (never {@code AuditRecorder}) so the
   * decoration cannot resolve back into itself while it is being created.
   */
  @Bean
  @Primary
  @Conditional(AuditAutoConfiguration.AnyRiskSinkPresent.class)
  ForwardingAuditRecorder forwardingAuditRecorder(
      @org.springframework.lang.Nullable EsAuditRecorder esAuditRecorder,
      @org.springframework.lang.Nullable JdbcAuditRecorder jdbcAuditRecorder,
      ObjectProvider<RiskForwarder> forwarder,
      ObjectProvider<RiskIngestSink> inProcess) {
    AuditRecorder delegate;
    if (esAuditRecorder != null) {
      delegate = esAuditRecorder;
    } else if (jdbcAuditRecorder != null) {
      delegate = jdbcAuditRecorder;
    } else {
      delegate = new NoopAuditRecorder();
    }
    RiskForwarder http = forwarder.getIfAvailable();
    if (http != null) {
      return new ForwardingAuditRecorder(delegate, http::ship);
    }
    RiskIngestSink local = inProcess.getIfAvailable();
    if (local != null) {
      return new ForwardingAuditRecorder(delegate, local::ingest);
    }
    return new ForwardingAuditRecorder(delegate, event -> {
    });
  }

  /** Non-blank {@code risk.forward.url} enables the HTTP forwarding path. */
  static class RiskForwardEnabled implements Condition {
    static final RiskForwardEnabled INSTANCE_MATCHER = new RiskForwardEnabled();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String url = context.getEnvironment().getProperty("risk.forward.url");
      return url != null && !url.isBlank();
    }
  }

  /** HTTP forwarder configured OR an in-process risk bridge bean exists. */
  static class AnyRiskSinkPresent implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      if (RiskForwardEnabled.INSTANCE_MATCHER.matches(context, metadata)) {
        return true;
      }
      try {
        return context.getBeanFactory()
            .getBeanNamesForType(RiskIngestSink.class, true, false).length > 0;
      } catch (RuntimeException e) {
        return false;
      }
    }
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
