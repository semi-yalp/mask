package io.sqlmask.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthConfigTest {

  private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void emptyEnvironmentDisablesEverything() {
    AuthConfig config = AuthConfig.fromEnv(Map.of());
    assertFalse(config.tokenEnabled());
    assertFalse(config.ldapEnabled());
    assertEquals(Duration.ofHours(8), config.tokenTtl());
    assertEquals("(uid={0})", config.userFilter());
    assertEquals("(member={dn})", config.groupFilter());
    assertEquals("", config.groupSearchBase());
  }

  @Test
  void strongSecretArmsTokens() {
    AuthConfig config = AuthConfig.fromEnv(Map.of("MASK_AUTH_SECRET", STRONG_SECRET));
    assertTrue(config.tokenEnabled());
    assertFalse(config.ldapEnabled());
  }

  @Test
  void weakSecretDoesNotArmTokens() {
    assertFalse(AuthConfig.fromEnv(Map.of("MASK_AUTH_SECRET", "too-short")).tokenEnabled());
  }

  @Test
  void ldapNeedsBothUrlAndBaseDn() {
    assertTrue(AuthConfig.fromEnv(Map.of(
        "MASK_AUTH_LDAP_URL", "ldap://ldap.example.org:389",
        "MASK_AUTH_LDAP_BASE_DN", "dc=example,dc=org")).ldapEnabled());
    assertFalse(AuthConfig.fromEnv(Map.of(
        "MASK_AUTH_LDAP_URL", "ldap://ldap.example.org:389")).ldapEnabled());
    assertFalse(AuthConfig.fromEnv(Map.of(
        "MASK_AUTH_LDAP_BASE_DN", "dc=example,dc=org")).ldapEnabled());
  }

  @Test
  void userSearchBaseFallsBackToBaseDn() {
    AuthConfig config = AuthConfig.fromEnv(Map.of(
        "MASK_AUTH_LDAP_URL", "ldap://ldap.example.org",
        "MASK_AUTH_LDAP_BASE_DN", "dc=example,dc=org"));
    assertEquals("dc=example,dc=org", config.userSearchBase());
  }

  @Test
  void customTtlAndFilterOverrides() {
    Map<String, String> env = new HashMap<>();
    env.put("MASK_AUTH_SECRET", STRONG_SECRET);
    env.put("MASK_AUTH_TOKEN_TTL", "PT30M");
    env.put("MASK_AUTH_LDAP_USER_FILTER", "(sAMAccountName={0})");
    env.put("MASK_AUTH_LDAP_USER_SEARCH_BASE", "ou=employees,dc=example,dc=org");
    env.put("MASK_AUTH_LDAP_GROUP_SEARCH_BASE", "ou=security-groups,dc=example,dc=org");
    AuthConfig config = AuthConfig.fromEnv(env);
    assertEquals(Duration.ofMinutes(30), config.tokenTtl());
    assertEquals("(sAMAccountName={0})", config.userFilter());
    assertEquals("ou=employees,dc=example,dc=org", config.userSearchBase());
    assertEquals("ou=security-groups,dc=example,dc=org", config.groupSearchBase());
  }

  @Test
  void invalidDurationFailsLoudly() {
    AuthException e = assertThrows(AuthException.class,
        () -> AuthConfig.fromEnv(Map.of("MASK_AUTH_TOKEN_TTL", "not-a-duration")));
    assertEquals(AuthException.Code.CONFIG_ERROR, e.code());
  }
}
