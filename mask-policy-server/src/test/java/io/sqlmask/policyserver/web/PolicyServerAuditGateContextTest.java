package io.sqlmask.policyserver.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Context-level gate test (gap §4.6 #1 / P0): with an admin/data API key
 * configured, the API key gate must reject unauthenticated requests through the
 * real servlet filter chain.
 *
 * <p>The production bean ({@code PolicyServerApplication.policyApiKeyFilter})
 * builds the filter from {@code System.getenv("SQLMASK_ADMIN_API_KEY")}/
 * {@code ...DATA_API_KEY} at bean creation — test processes cannot set process
 * environment variables — so a {@code BeanFactoryPostProcessor} drops that bean
 * definition and this test registers the closed-key filter under the exact
 * production URL patterns ({@code /api/instances/*}, {@code /api/effective/*}).
 * The companion unit test {@code io.sqlmask.common.web.ApiKeyFilterTest} pins
 * {@code requiredKey} routing directly.
 *
 * <p>{@code ApiKeyFilter#doFilter} keys off {@code getServletPath()}, which
 * a real container sets to the decoded request path but MockMvc leaves empty —
 * each request therefore carries an explicit servletPath (same shape the unit
 * tests and the container use), keeping the path gates faithful.
 *
 * <p>Audit routing: the audit query surface lives in mask-core (:8080) and the
 * nginx frontend routes /api/audit there; policy-server deliberately serves no
 * audit endpoint and maps no gate onto it. An /api/audit/** request reaching
 * this service falls through the gate and is answered by the dispatcher as a
 * 404 {@code NOT_FOUND} (never a 401 from the gate, never the old catch-all
 * 500).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyServerAuditGateContextTest {

  private static final String ADMIN_KEY = "test-admin-key";
  private static final String DATA_KEY = "test-data-key";

  @Autowired
  private MockMvc mvc;

  @TestConfiguration
  static class GateConfig {

    /** Remove the production bean (env-keyed, open in tests) so the closed-key one below is the sole gate. */
    @Bean
    static BeanFactoryPostProcessor dropProductionApiKeyFilter() {
      return beanFactory -> {
        if (beanFactory instanceof BeanDefinitionRegistry registry
            && beanFactory.containsBeanDefinition("policyApiKeyFilter")) {
          registry.removeBeanDefinition("policyApiKeyFilter");
        }
      };
    }

    @Bean
    FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter> testPolicyApiKeyFilter() {
      io.sqlmask.common.web.ApiKeyFilter filter = io.sqlmask.common.web.ApiKeyFilter.surfaces(
          new io.sqlmask.common.web.ApiKeyFilter.Surface("/api/instances", ADMIN_KEY),
          new io.sqlmask.common.web.ApiKeyFilter.Surface("/api/effective", DATA_KEY));
      FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter> registration =
          new FilterRegistrationBean<>(filter);
      registration.addUrlPatterns("/api/instances/*", "/api/effective/*");
      registration.setOrder(1);
      return registration;
    }
  }

  @Test
  void registeredAdminSurfaceRejectsUnauthenticatedRequests() throws Exception {
    mvc.perform(get("/api/instances").with(servletPath("/api/instances")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/instances").with(servletPath("/api/instances"))
            .header("X-Api-Key", "wrong-key"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/instances").with(servletPath("/api/instances"))
            .header("X-Api-Key", ADMIN_KEY))
        .andExpect(status().isOk());
  }

  @Test
  void registeredDataSurfaceRejectsUnauthenticatedRequests() throws Exception {
    // gate on /api/effective/* is keyed by the DATA key; the admin key must not open it
    mvc.perform(get("/api/effective/pg_prod").with(servletPath("/api/effective/pg_prod")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/effective/pg_prod").with(servletPath("/api/effective/pg_prod"))
            .header("X-Api-Key", ADMIN_KEY))
        .andExpect(status().isUnauthorized());
    // data key passes the gate; the instance is absent on this test store -> 404 behind the gate
    mvc.perform(get("/api/effective/pg_prod").with(servletPath("/api/effective/pg_prod"))
            .header("X-Api-Key", DATA_KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  @Test
  void auditSurfaceIsNotServedByThisService() throws Exception {
    // /api/audit belongs to mask-core; the nginx frontend routes it there. This
    // service maps no gate onto it, so any key (or none) passes the filter and
    // the dispatcher answers 404 NOT_FOUND — not a 401, not the old 500.
    mvc.perform(get("/api/audit/events").with(servletPath("/api/audit/events")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(get("/api/audit/events").with(servletPath("/api/audit/events"))
            .header("X-Api-Key", ADMIN_KEY))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/audit/events").with(servletPath("/api/audit/events"))
            .header("X-Api-Key", DATA_KEY))
        .andExpect(status().isNotFound());
  }

  /** Minimal container emulation: servletPath = decoded request path without context path. */
  private static RequestPostProcessor servletPath(String path) {
    return request -> {
      ((MockHttpServletRequest) request).setServletPath(path);
      return request;
    };
  }
}