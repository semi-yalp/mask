package io.sqlmask.server;

import io.sqlmask.audit.AuditSearchClient;
import io.sqlmask.audit.AuditSearchResult;
import io.sqlmask.auth.AuthConfig;
import io.sqlmask.auth.AuthPrincipal;
import io.sqlmask.auth.AuthTokenService;
import io.sqlmask.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role matrix on the audit-search surface: the bearer gate demands AUDITOR
 * or above (design §2.5), the API-key path is untouched (open here — no env
 * key configured in tests), and a verified token also satisfies the admin
 * key gate that owns /api/audit/*.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class LdapAuditSurfaceTest {

  private static final String SECRET = "test-secret-0123456789abcdef0123456789";

  @Autowired
  MockMvc mockMvc;

  @MockBean
  AuditSearchClient searchClient;

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
  void plainUserTokenIsForbiddenFromAuditSearch() throws Exception {
    mockMvc.perform(get("/api/audit/events").servletPath("/api/audit/events")
            .header("Authorization", "Bearer " + tokenFor(Role.USER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void auditorTokenPassesWithoutApiKey() throws Exception {
    when(searchClient.search(any(io.sqlmask.audit.AuditQuery.class)))
        .thenReturn(new AuditSearchResult(0, List.of()));
    mockMvc.perform(get("/api/audit/events").servletPath("/api/audit/events")
            .header("Authorization", "Bearer " + tokenFor(Role.AUDITOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void invalidTokenIsUnauthorizedEvenForAuditSearch() throws Exception {
    mockMvc.perform(get("/api/audit/events").servletPath("/api/audit/events")
            .header("Authorization", "Bearer forged.token.value"))
        .andExpect(status().isUnauthorized());
  }
}
