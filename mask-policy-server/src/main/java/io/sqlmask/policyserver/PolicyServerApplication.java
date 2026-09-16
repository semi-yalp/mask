package io.sqlmask.policyserver;

import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.JdbcPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import io.sqlmask.policyserver.web.MetadataStructureFetcher;
import io.sqlmask.policyserver.web.PolicyApiKeyFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Standalone policy service: engine instances, UDF registry and policies with
 * their admin REST, plus the subject-parameterized effective-config data
 * plane. The JDBC store is used whenever a datasource is configured (the
 * production default, PostgreSQL); tests exclude the datasource
 * auto-configuration and fall back to the in-memory store.
 */
@SpringBootApplication
public class PolicyServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(PolicyServerApplication.class, args);
  }

  @Bean
  PolicyValidator policyValidator() {
    return new PolicyValidator();
  }

  @Bean
  PolicyService policyService(PolicyStore store, PolicyValidator validator) {
    return new PolicyService(store, validator);
  }

  @Bean
  MetadataStructureFetcher metadataStructureFetcher() {
    return new MetadataStructureFetcher.HttpMetadataStructureFetcher();
  }

  @Bean
  PolicyStore policyStore(ObjectProvider<JdbcTemplate> jdbc) {
    JdbcTemplate template = jdbc.getIfAvailable();
    return template != null ? new JdbcPolicyStore(template) : new InMemoryPolicyStore();
  }

  @Bean
  io.sqlmask.audit.AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new io.sqlmask.audit.AuditAdminHelper(recorder,
        "sql-mask",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }

  @Bean
  FilterRegistrationBean<PolicyApiKeyFilter> policyApiKeyFilter() {
    FilterRegistrationBean<PolicyApiKeyFilter> registration =
        new FilterRegistrationBean<>(new PolicyApiKeyFilter(
            System.getenv("SQLMASK_ADMIN_API_KEY"), System.getenv("SQLMASK_DATA_API_KEY")));
    registration.addUrlPatterns("/api/instances/*", "/api/effective/*");
    registration.setOrder(1);
    return registration;
  }
}
