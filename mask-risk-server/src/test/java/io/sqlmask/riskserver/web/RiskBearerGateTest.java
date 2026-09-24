package io.sqlmask.riskserver.web;

import io.sqlmask.auth.AuthConfig;
import io.sqlmask.auth.AuthPrincipal;
import io.sqlmask.auth.AuthTokenService;
import io.sqlmask.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The risk console ships only {@code Authorization: Bearer} once a user is
 * signed in (frontend http.ts), so the risk service must accept verified
 * bearer tokens exactly like the other console-facing services — otherwise
 * every LDAP user would be 401'd and logged out on the risk pages. The API
 * key path stays intact for machine ingest.
 */
@SpringBootTest(properties = {
    "risk.api-key=risk-secret",
    "risk.store.persistence-path=", // keep the test out of data/
    "risk.demo.seed-on-start=false",
    "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
class RiskBearerGateTest {

  private static final String SECRET = "test-secret-0123456789abcdef0123456789";

  @Autowired
  MockMvc mockMvc;

  @TestConfiguration
  static class AuthWiring {
    @Bean
    AuthConfig authConfig() {
      return AuthConfig.fromEnv(Map.of("MASK_AUTH_SECRET", SECRET));
    }
  }

  private String tokenFor(Role role) {
    return new AuthTokenService(SECRET, Duration.ofHours(1))
        .issue(new AuthPrincipal("amy", "Amy", role, List.of()), Instant.now()).token();
  }

  @Test
  void withoutKeyOrTokenIsRejected() throws Exception {
    mockMvc.perform(get("/api/risk/rules").servletPath("/api/risk/rules"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bearerTokenPassesWithoutApiKey() throws Exception {
    mockMvc.perform(get("/api/risk/rules").servletPath("/api/risk/rules")
            .header("Authorization", "Bearer " + tokenFor(Role.USER)))
        .andExpect(status().isOk());
  }

  @Test
  void invalidTokenIsRejected() throws Exception {
    mockMvc.perform(get("/api/risk/rules").servletPath("/api/risk/rules")
            .header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void apiKeyStillPasses() throws Exception {
    mockMvc.perform(get("/api/risk/rules").servletPath("/api/risk/rules")
            .header("X-Api-Key", "risk-secret"))
        .andExpect(status().isOk());
  }
}