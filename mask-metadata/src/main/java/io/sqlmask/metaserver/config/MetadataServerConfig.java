package io.sqlmask.metaserver.config;

import io.sqlmask.audit.AuditAdminHelper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata domain wiring (was the standalone mask-metadata service):
 * instance registry, structure collection and the data-plane snapshot API.
 * Authentication is a cross-cutting concern handled centrally in mask-server.
 */
@Configuration
public class MetadataServerConfig {

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
}
