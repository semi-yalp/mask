package io.sqlmask.policyserver;

import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import io.sqlmask.policyserver.web.EffectiveConfigController;
import io.sqlmask.policyserver.web.PolicyAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring context assembly smoke (gap §4.6 #6 / §6.4): the test classpath kills
 * the datasource auto-configuration (src/test/resources/application.yml), so the
 * {@code PolicyServerApplication.policyStore} fallback branch must pick the
 * in-memory store; the sibling {@link PolicyServerJdbcContextTest} covers the
 * JDBC branch in the container. Also pins the API-key filter registration bean.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyServerApplicationContextTest {

  @Autowired
  private ApplicationContext context;

  @Test
  void contextLoads() {
    assertNotNull(context.getBean(PolicyServerApplication.class));
    assertNotNull(context.getBean(EffectiveConfigController.class));
    assertNotNull(context.getBean(PolicyAdminController.class));
  }

  @Test
  void noDatasourceFallsBackToInMemoryStore() {
    assertTrue(context.getBean(PolicyStore.class) instanceof InMemoryPolicyStore,
        "without a JdbcTemplate the store must fall back to InMemoryPolicyStore");
  }

  @Test
  void apiKeyFilterBeanIsRegistered() {
    FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter> gate = context.getBeansOfType(
            FilterRegistrationBean.class).values().stream()
        .filter(r -> r.getFilter() instanceof io.sqlmask.common.web.ApiKeyFilter)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no FilterRegistrationBean for ApiKeyFilter"));
    assertTrue(gate.getUrlPatterns().contains("/api/instances/*"));
  }
}