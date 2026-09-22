package io.sqlmask.policyserver;

import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.JdbcPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import io.sqlmask.policyserver.web.MetadataStructureFetcher;
import io.sqlmask.policyserver.web.PolicyApiKeyFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(PolicyServerApplication.class);

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
    if (template == null) {
      log.info("no JdbcTemplate available: falling back to InMemoryPolicyStore "
          + "(non-persistent; configure a datasource for JdbcPolicyStore)");
      return new InMemoryPolicyStore();
    }
    log.info("JdbcTemplate available: using JdbcPolicyStore");
    return new JdbcPolicyStore(template);
  }

  @Bean
  io.sqlmask.audit.AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new io.sqlmask.audit.AuditAdminHelper(recorder,
        "mask-policy",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }

  @Bean
  FilterRegistrationBean<PolicyApiKeyFilter> policyApiKeyFilter() {
    String adminKey = System.getenv("SQLMASK_ADMIN_API_KEY");
    String dataKey = System.getenv("SQLMASK_DATA_API_KEY");
    if (adminKey == null || adminKey.isBlank()) {
      log.warn("SQLMASK_ADMIN_API_KEY is not configured: the admin surface "
          + "(/api/instances/**, instance/policy/UDF management) is OPEN to anyone "
          + "who can reach this service");
    }
    if (dataKey == null || dataKey.isBlank()) {
      log.warn("SQLMASK_DATA_API_KEY is not configured: the data surface "
          + "(/api/effective/**, compiled masking rules per subject) is OPEN to anyone "
          + "who can reach this service");
    }
    FilterRegistrationBean<PolicyApiKeyFilter> registration =
        new FilterRegistrationBean<>(new PolicyApiKeyFilter(adminKey, dataKey));
    registration.addUrlPatterns("/api/instances/*", "/api/effective/*");
    registration.setOrder(1);
    return registration;
  }
}
