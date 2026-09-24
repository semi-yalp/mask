package io.sqlmask.metaserver.config;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.metaserver.classification.ClassificationStore;
import io.sqlmask.metaserver.classification.InMemoryClassificationStore;
import io.sqlmask.metaserver.classification.JdbcClassificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Metadata domain wiring (was the standalone mask-metadata service):
 * instance registry, structure collection and the data-plane snapshot API.
 * Authentication is a cross-cutting concern handled centrally in mask-server.
 */
@Configuration
public class MetadataServerConfig {

  private static final Logger log = LoggerFactory.getLogger(MetadataServerConfig.class);

  @Bean(name = "metadataAuditAdminHelper")
  public AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new AuditAdminHelper(recorder, "mask-metadata",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }

  @Bean
  public io.sqlmask.metaserver.service.IntrospectorFactory introspectorFactory() {
    return io.sqlmask.introspect.MetadataIntrospectors::byEngine;
  }

  /**
   * JDBC classification store whenever a datasource is configured (the
   * monolith default); a context without one (unit slices) falls back to the
   * non-persistent in-memory store.
   */
  @Bean
  public ClassificationStore classificationStore(ObjectProvider<JdbcTemplate> jdbc) {
    JdbcTemplate template = jdbc.getIfAvailable();
    if (template == null) {
      log.info("no JdbcTemplate available: falling back to InMemoryClassificationStore "
          + "(non-persistent; configure a datasource for JdbcClassificationStore)");
      return new InMemoryClassificationStore();
    }
    log.info("JdbcTemplate available: using JdbcClassificationStore");
    return new JdbcClassificationStore(template);
  }
}
