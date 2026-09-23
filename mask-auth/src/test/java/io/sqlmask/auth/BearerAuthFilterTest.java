package io.sqlmask.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearerAuthFilterTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private final AuthTokenService tokens = new AuthTokenService(SECRET, Duration.ofHours(8));

  private final BearerAuthFilter filter = new BearerAuthFilter(tokens, new AuthRule.Builder()
      .readOnly("/api/instances", Role.USER, Role.ADMIN)
      .prefix("/api/audit", Role.AUDITOR)
      .build());

  private String tokenFor(Role role) {
    return tokens.issue(new AuthPrincipal("amy", "Amy", role, List.of("g1")), Instant.now()).token();
  }

  private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    request.setAttribute("__chain_invoked", chain.getRequest() != null);
    return response;
  }

  @Test
  void requestWithoutBearerHeaderPassesThroughUntouched() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    request.setServletPath("/api/instances");
    MockHttpServletResponse response = run(request);
    assertEquals(200, response.getStatus());
    assertNull(request.getAttribute(AuthTokens.PRINCIPAL_ATTRIBUTE));
    assertEquals(Boolean.TRUE, request.getAttribute("__chain_invoked"));
  }

  @Test
  void validTokenStampsIdentityAndPasses() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances/x");
    request.setServletPath("/api/instances/x");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));
    MockHttpServletResponse response = run(request);

    assertEquals(200, response.getStatus());
    AuthPrincipal principal = (AuthPrincipal) request.getAttribute(AuthTokens.PRINCIPAL_ATTRIBUTE);
    assertEquals("amy", principal.username());
    assertEquals(Role.USER, principal.role());
    assertEquals(Boolean.TRUE, request.getAttribute(AuthTokens.BEARER_AUTHENTICATED_ATTRIBUTE));
    assertEquals("LDAP", request.getAttribute(AuthTokens.AUTH_KIND_ATTRIBUTE));
    assertEquals("amy", request.getAttribute(AuthTokens.USER_ATTRIBUTE));
  }

  @Test
  void writeOnReadOnlySurfaceNeedsAdmin() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/instances");
    request.setServletPath("/api/instances");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));
    MockHttpServletResponse response = run(request);
    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("FORBIDDEN"));
  }

  @Test
  void adminPassesWriteOnReadOnlySurface() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/instances/x");
    request.setServletPath("/api/instances/x");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.ADMIN));
    MockHttpServletResponse response = run(request);
    assertEquals(200, response.getStatus());
  }

  @Test
  void auditorSurfaceRejectsPlainUser() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));
    MockHttpServletResponse response = run(request);
    assertEquals(403, response.getStatus());
  }

  @Test
  void auditorPassesAuditorSurface() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.AUDITOR));
    MockHttpServletResponse response = run(request);
    assertEquals(200, response.getStatus());
  }

  @Test
  void invalidTokenIsUnauthorizedWithoutFallback() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    request.setServletPath("/api/instances");
    request.addHeader("Authorization", "Bearer garbage.token.here");
    MockHttpServletResponse response = run(request);
    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    assertNull(request.getAttribute(AuthTokens.PRINCIPAL_ATTRIBUTE));
  }

  @Test
  void expiredTokenIsUnauthorized() throws Exception {
    Instant now = Instant.now();
    String expired = tokens.issue(new AuthPrincipal("amy", "Amy", Role.ADMIN, List.of()),
        now.minusSeconds(Duration.ofHours(9).toSeconds())).token();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    request.addHeader("Authorization", "Bearer " + expired);
    MockHttpServletResponse response = run(request);
    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("token expired"));
  }

  @Test
  void pathOutsideEveryRuleIsAllowedOnceAuthenticated() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/other");
    request.setServletPath("/api/other");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));
    MockHttpServletResponse response = run(request);
    assertEquals(200, response.getStatus());
  }

  @Test
  void encodedPrefixDoesNotBypassRules() throws Exception {
    // servletPath is the decoded form; a raw /api/%61udit stays decoded here,
    // and the rule compares against that same decoded path
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    request.addHeader("Authorization", "Bearer " + tokenFor(Role.USER));
    assertEquals(403, run(request).getStatus());
  }
}
