package io.sqlmask.policyserver.web;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.auth.AuthConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-chain LDAP login → bearer token → role-gated admin surface, through
 * the real filter registrations of the production application context. The
 * in-memory LDAP directory plays the corporate directory: amy (admin group),
 * bob (auditor group), carol (plain user).
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
class AuthLdapEndpointTest {

  private static final String SECRET = "test-secret-0123456789abcdef0123456789";

  private static final String INSTANCE_BODY = """
      {"name": "ldap_test_instance", "dialect": "postgresql",
       "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
                   "columns": [{"name": "phone", "type": "varchar"}]}]}
      """;

  private static InMemoryDirectoryServer ldap;

  @Autowired
  MockMvc mockMvc;

  @MockBean
  AuditRecorder auditRecorder;

  @BeforeAll
  static void startLdap() throws Exception {
    InMemoryDirectoryServerConfig config =
        new InMemoryDirectoryServerConfig("dc=example,dc=org");
    config.setSchema(null);
    ldap = new InMemoryDirectoryServer(config);
    ldap.startListening();
    ldap.add("dn: dc=example,dc=org\nobjectClass: domain\ndc: example".split("\n"));
    ldap.add("dn: ou=people,dc=example,dc=org\nobjectClass: organizationalUnit\nou: people".split("\n"));
    ldap.add(("dn: uid=amy,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: amy\n"
        + "cn: Amy Admin\nsn: Admin\nuserPassword: amy-secret\n"
        + "memberOf: cn=mask-admins,ou=groups,dc=example,dc=org").split("\n"));
    ldap.add(("dn: uid=bob,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: bob\n"
        + "cn: Bob Auditor\nsn: Auditor\nuserPassword: bob-secret\n"
        + "memberOf: cn=mask-auditors,ou=groups,dc=example,dc=org").split("\n"));
    ldap.add(("dn: uid=carol,ou=people,dc=example,dc=org\nobjectClass: inetOrgPerson\nuid: carol\n"
        + "cn: Carol User\nsn: User\nuserPassword: carol-secret").split("\n"));
  }

  @AfterAll
  static void stopLdap() {
    if (ldap != null) {
      ldap.shutDown(true);
    }
  }

  /** Overrides just the env-reading bean; every other wiring is the production one. */
  @TestConfiguration
  static class LdapWiring {
    @Bean
    AuthConfig authConfig() {
      return AuthConfig.fromEnv(Map.of(
          "MASK_AUTH_SECRET", SECRET,
          "MASK_AUTH_LDAP_URL", "ldap://127.0.0.1:" + ldap.getListenPort(),
          "MASK_AUTH_LDAP_BASE_DN", "dc=example,dc=org",
          "MASK_AUTH_ADMIN_GROUPS", "mask-admins",
          "MASK_AUTH_AUDITOR_GROUPS", "mask-auditors"));
    }
  }

  private String login(String username, String password) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
        .readTree(result.getResponse().getContentAsString())
        .path("token").asText();
  }

  @Test
  void loginReturnsTokenRoleAndGroupsFromLdap() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"amy\",\"password\":\"amy-secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(jsonPath("$.user.username").value("amy"))
        .andExpect(jsonPath("$.user.displayName").value("Amy Admin"))
        .andExpect(jsonPath("$.user.role").value("ADMIN"))
        .andExpect(jsonPath("$.user.groups[0]").value("mask-admins"))
        .andExpect(jsonPath("$.expiresInSeconds").value(8 * 3600));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"bob\",\"password\":\"bob-secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("AUDITOR"));

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"carol\",\"password\":\"carol-secret\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("USER"));
  }

  @Test
  void wrongPasswordAndUnknownUserReturnTheSameBody() throws Exception {
    String wrong = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"carol\",\"password\":\"nope\"}"))
        .andExpect(status().isUnauthorized())
        .andReturn().getResponse().getContentAsString();
    String unknown = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ghost\",\"password\":\"whatever\"}"))
        .andExpect(status().isUnauthorized())
        .andReturn().getResponse().getContentAsString();
    assertEquals(wrong, unknown);
  }

  @Test
  void modeReportsLdapEnabled() throws Exception {
    mockMvc.perform(get("/api/auth/mode"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ldap").value(true));
  }

  @Test
  void meEchoesTheVerifiedIdentity() throws Exception {
    String token = login("carol", "carol-secret");
    mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("carol"))
        .andExpect(jsonPath("$.role").value("USER"));
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bearerTokenSatisfiesTheAdminSurfaceWithoutApiKey() throws Exception {
    String token = login("carol", "carol-secret");
    mockMvc.perform(get("/api/instances").servletPath("/api/instances")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void garbageTokenIsRejectedWithoutFallingBackToOpenAccess() throws Exception {
    mockMvc.perform(get("/api/instances").servletPath("/api/instances")
            .header("Authorization", "Bearer not.a.token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void plainUserCannotWriteInstancesWhileAdminCan() throws Exception {
    String carol = login("carol", "carol-secret");
    String amy = login("amy", "amy-secret");

    mockMvc.perform(post("/api/instances").servletPath("/api/instances")
            .header("Authorization", "Bearer " + carol)
            .contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    mockMvc.perform(post("/api/instances").servletPath("/api/instances")
            .header("Authorization", "Bearer " + amy)
            .contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("ldap_test_instance"));
  }

  @Test
  void effectiveSubjectComesFromTheTokenNotTheQueryString() throws Exception {
    String carol = login("carol", "carol-secret");
    // instance does not exist → POLICY_INSTANCE_NOT_FOUND, but the audit event
    // still records the subject that actually asked for the config
    mockMvc.perform(get("/api/effective/does_not_exist")
            .servletPath("/api/effective/does_not_exist")
            .queryParam("user", "root")
            .queryParam("groups", "wheel")
            .header("Authorization", "Bearer " + carol))
        .andExpect(status().is4xxClientError());

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditRecorder, atLeastOnce()).record(captor.capture());
    List<AuditEvent> pulls = captor.getAllValues().stream()
        .filter(e -> AuditEvent.EFFECTIVE_PULL.equals(e.eventType())
            && "does_not_exist".equals(e.instance()))
        .toList();
    assertEquals(1, pulls.size(), "exactly one effective pull should be audited");
    assertEquals("carol", pulls.get(0).actorUser(), "subject must be the verified identity");
    assertEquals(List.of(), pulls.get(0).actorGroups(), "query-param groups must be ignored");
    assertEquals("LDAP", pulls.get(0).authKind());
  }

  @Test
  void noTokenNoKeyKeepsThePreLdapOpenBehaviour() throws Exception {
    // no env API keys in tests, and no bearer header: the surface stays open
    mockMvc.perform(get("/api/instances"))
        .andExpect(status().isOk());
  }

  @Test
  void emptyLoginPayloadIsRejectedWithoutTouchingLdap() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
