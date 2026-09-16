package io.sqlmask.metaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Metadata microservice entry point: instance registry, table structures and collection. */
@SpringBootApplication
public class MetadataServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(MetadataServerApplication.class, args);
  }

  @Bean
  io.sqlmask.audit.AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new io.sqlmask.audit.AuditAdminHelper(recorder, "mask-metadata",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }
}
