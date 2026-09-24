package io.sqlmask.query.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.auth.AuthConfig;
import io.sqlmask.query.service.QueryModels.QueryResult;
import io.sqlmask.query.service.QueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bearer-gated query data plane: a logged-in console user may query, and the
 * audited subject is the verified identity rather than the request body's
 * caller-asserted user/groups. The QueryApiKeyFilter is fail-closed with no
 * env key configured, so every request here must come in with a token.
 */
@SpringBootTest(properties = {
    "upstream.metadata-base-url=http://localhost:1",
    "upstream.rewrite-base-url=http://localhost:1",
    "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
class QueryLdapBearerTest {

  private static final String SECRET = "test-secret-0123456789abcdef0123456789";

  @Autowired
  MockMvc mockMvc;

  @MockBean
  QueryService queryService;

  @MockBean
  AuditRecorder auditRecorder;

  @TestConfiguration
  static class AuthWiring {
    @Bean
    AuthConfig authConfig() {
      return AuthConfig.fromEnv(Map.of("MASK_AUTH_SECRET", SECRET));
    }
  }

  private static final String QUERY_BODY = """
      {"instance": "pg", "sql": "SELECT phone FROM customer", "user": "root",
       "groups": ["wheel"], "maxRows": 10}
      """;

  @Test
  void withoutTokenTheFailClosedKeyGateRejects() throws Exception {
    mockMvc.perform(post("/api/v1/query").servletPath("/api/v1/query")
            .contentType(MediaType.APPLICATION_JSON).content(QUERY_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void garbageTokenIsRejected() throws Exception {
    mockMvc.perform(post("/api/v1/query").servletPath("/api/v1/query")
            .header("Authorization", "Bearer nope")
            .contentType(MediaType.APPLICATION_JSON).content(QUERY_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tokenGrantsQueryAndTheAuditedSubjectIsTheTokenIdentity() throws Exception {
    // issue a token directly: no LDAP needed to verify the gate + subject binding
    String token = new io.sqlmask.auth.AuthTokenService(SECRET, java.time.Duration.ofHours(1))
        .issue(new io.sqlmask.auth.AuthPrincipal("carol", "Carol", io.sqlmask.auth.Role.USER,
            List.of("mask-users")), java.time.Instant.now()).token();

    when(queryService.execute(any(), any())).thenReturn(new QueryResult(
        "pg", "postgresql", List.of(), List.of(), 0, false, true, false, 5, null));

    var mvcResult = mockMvc.perform(post("/api/v1/query").servletPath("/api/v1/query")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(QUERY_BODY))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditRecorder).record(captor.capture());
    AuditEvent event = captor.getValue();
    assertEquals("carol", event.actorUser(), "audited subject must be the verified identity");
    assertEquals(List.of("mask-users"), event.actorGroups());
    assertEquals("LDAP", event.authKind());
    assertEquals("pg", event.instance());
  }
}
