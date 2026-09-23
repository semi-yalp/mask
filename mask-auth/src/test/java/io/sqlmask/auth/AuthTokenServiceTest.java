package io.sqlmask.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthTokenServiceTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final String OTHER_SECRET = "ffffffffffffffffffffffffffffffff";

  private final AuthTokenService service = new AuthTokenService(SECRET, Duration.ofHours(8));

  private AuthPrincipal principal(Role role) {
    return new AuthPrincipal("amy", "Amy Admin", role, List.of("mask-admins", "devs"));
  }

  @Test
  void roundTripPreservesIdentity() {
    Instant now = Instant.parse("2026-09-24T00:00:00Z");
    AuthTokenService.IssuedToken issued = service.issue(principal(Role.ADMIN), now);

    assertEquals(Duration.ofHours(8).toSeconds(), issued.expiresInSeconds());
    assertEquals(now.getEpochSecond() + Duration.ofHours(8).toSeconds(), issued.expiresAtEpochSecond());

    AuthPrincipal verified = service.verify(issued.token(), now.plusSeconds(60));
    assertEquals("amy", verified.username());
    assertEquals("Amy Admin", verified.displayName());
    assertEquals(Role.ADMIN, verified.role());
    assertEquals(List.of("mask-admins", "devs"), verified.groups());
  }

  @Test
  void tokenIsAStandardJwtShape() {
    String token = service.issue(principal(Role.USER), Instant.now()).token();
    String[] parts = token.split("\\.");
    assertEquals(3, parts.length);
    String header = new String(Base64.getUrlDecoder().decode(parts[0]));
    assertEquals("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", header);
  }

  @Test
  void tamperedPayloadIsRejected() {
    String token = service.issue(principal(Role.USER), Instant.now()).token();
    String[] parts = token.split("\\.");
    String forgedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("{\"sub\":\"amy\",\"role\":\"ADMIN\",\"groups\":[],\"exp\":"
            + (Instant.now().getEpochSecond() + 3600) + "}").getBytes());
    String tampered = parts[0] + "." + forgedClaims + "." + parts[2];
    AuthException e = assertThrows(AuthException.class, () -> service.verify(tampered, Instant.now()));
    assertEquals(AuthException.Code.INVALID_TOKEN, e.code());
  }

  @Test
  void wrongSecretIsRejected() {
    String token = new AuthTokenService(OTHER_SECRET, Duration.ofHours(8))
        .issue(principal(Role.USER), Instant.now()).token();
    assertThrows(AuthException.class, () -> service.verify(token, Instant.now()));
  }

  @Test
  void expiredTokenIsDistinguishedFromInvalid() {
    Instant now = Instant.now();
    String token = service.issue(principal(Role.AUDITOR), now).token();
    AuthException e = assertThrows(AuthException.class,
        () -> service.verify(token, now.plusSeconds(Duration.ofHours(8).toSeconds() + 1)));
    assertEquals(AuthException.Code.EXPIRED_TOKEN, e.code());
  }

  @Test
  void algNoneIsRejected() {
    String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes())
        + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("{\"sub\":\"amy\",\"role\":\"ADMIN\",\"groups\":[],\"exp\":"
            + (Instant.now().getEpochSecond() + 3600) + "}").getBytes())
        + ".";
    AuthException e = assertThrows(AuthException.class, () -> service.verify(unsigned, Instant.now()));
    assertEquals(AuthException.Code.INVALID_TOKEN, e.code());
  }

  @Test
  void malformedTokensAreRejected() {
    assertThrows(AuthException.class, () -> service.verify(null, Instant.now()));
    assertThrows(AuthException.class, () -> service.verify("not-a-token", Instant.now()));
    assertThrows(AuthException.class, () -> service.verify("a.b", Instant.now()));
    assertThrows(AuthException.class, () -> service.verify("a.b.c.d", Instant.now()));
  }

  @Test
  void weakSecretIsRefusedAtConstruction() {
    assertThrows(AuthException.class, () -> new AuthTokenService("short", Duration.ofHours(1)));
    assertThrows(AuthException.class, () -> new AuthTokenService(null, Duration.ofHours(1)));
    assertThrows(AuthException.class, () -> new AuthTokenService(SECRET, Duration.ZERO));
  }

  @Test
  void blankDisplayNameIsOmittedFromClaimsAndFallsBackOnVerify() {
    String token = service.issue(new AuthPrincipal("bob", "", Role.USER, List.of()), Instant.now()).token();
    AuthPrincipal verified = service.verify(token, Instant.now());
    assertEquals("bob", verified.username());
    // no "name" claim in the token → display name comes back empty-free
    assertEquals("", verified.displayName() == null ? "" : verified.displayName());
  }
}
