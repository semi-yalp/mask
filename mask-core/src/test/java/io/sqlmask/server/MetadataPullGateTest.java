package io.sqlmask.server;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The metadata pull dials out with operator credentials: it is an admin
 * action behind the same fail-closed gate as /admin/cache/refresh. No
 * SQLMASK_ADMIN_API_KEY ⇒ reject instead of letting anyone probe intranet
 * hosts through the service. With MASK_AUTH_SECRET armed the bearer gate
 * owns the endpoint too: a verified console token satisfies the admin-key
 * filter (auth.bearer ⇒ key check skipped), so the rule set — not the key —
 * decides which console roles may pull. Only ADMIN may; a USER token is 403.
 */
@SpringBootTest(properties = {
    "SQLMASK_ADMIN_API_KEY=metadata-secret",
    "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
class MetadataPullGateTest {

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

  private static final String BODY =
      "{\"engine\":\"bogus\",\"database\":\"d\",\"user\":\"u\",\"password\":\"p\"}";

  @Test
  void pullWithoutKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullWithWrongKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("X-Api-Key", "nope")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullWithAdminKeyPassesTheGate() throws Exception {
    // 网关放行后停在 CONFIG_ERROR(未知引擎)而非 401——证明鉴权已过、且不会外连
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("X-Api-Key", "metadata-secret")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest());
  }

  @Test
  void userTokenIsForbiddenFromMetadataPull() throws Exception {
    // USER token 已通过签名校验(auth.bearer=TRUE 会让 admin-key 网关跳过),
    // 只有 bearer 规则能拦住它——/api/metadata 必须要求 ADMIN
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("Authorization", "Bearer " + tokenFor(Role.USER))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void adminTokenPassesTheGateWithoutApiKey() throws Exception {
    // ADMIN token 走 bearer 直通 admin-key 网关,同样停在 bogus 引擎的 400
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("Authorization", "Bearer " + tokenFor(Role.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest());
  }
}
