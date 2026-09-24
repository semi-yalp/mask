package io.sqlmask.policyserver.config;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.PolicyValidator;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.JdbcPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import io.sqlmask.policyserver.web.MetadataStructureFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Policy-admin domain wiring: engine instances, policies, the UDF registry
 * and the effective-config compiler. The JDBC store is used whenever a
 * datasource is configured (the monolith default); a context without one
 * (unit slices) falls back to the in-memory store.
 */
@Configuration
public class PolicyAdminConfig {

  private static final Logger log = LoggerFactory.getLogger(PolicyAdminConfig.class);

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

  @Bean(name = "policyAuditAdminHelper")
  AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new AuditAdminHelper(recorder,
        "mask-policy",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }

}
