package io.sqlmask.policyserver;

import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.connection.JdbcEngineAccess;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.JdbcPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v3 policy micro-service entry point (port 8081). No business-side
 * authentication this version; the {@code PolicyApiKeyFilter} is intentionally
 * absent. Storage defaults to PostgreSQL ({@code POLICY_PG_*}) when a
 * {@code JdbcTemplate} is present and falls back to the in-memory store for
 * tests.
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
  PolicyStore policyStore(ObjectProvider<JdbcTemplate> jdbc) {
    JdbcTemplate template = jdbc.getIfAvailable();
    if (template != null) {
      return new JdbcPolicyStore(template);
    }
    log.warn("no JdbcTemplate on classpath/context; policy state will be in-memory (NOT persisted)");
    return new InMemoryPolicyStore();
  }

  @Bean
  PolicyService policyService(PolicyStore store, PolicyValidator validator) {
    return new PolicyService(store, validator);
  }

  @Bean
  EngineAccess engineAccess() {
    return new JdbcEngineAccess();
  }

  @Bean
  io.sqlmask.policyserver.app.SuggestService suggestService(PolicyStore store,
      EngineAccess access) {
    return new io.sqlmask.policyserver.app.SuggestService(store, access);
  }
}