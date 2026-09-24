package io.sqlmask.auth;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Environment-driven configuration (the same convention the per-service
 * API-key filters use: {@code System.getenv} read at wiring time, no
 * application.yml coupling). All {@code MASK_AUTH_*} variables are optional;
 * the feature arms itself only when its required pair is present.
 *
 * <ul>
 *   <li>{@code MASK_AUTH_SECRET} — HS256 signing key, UTF-8, ≥32 bytes.
 *       Present ⇒ token verification is armed on every console-facing service
 *       that shares the secret. Absent ⇒ bearer tokens are not accepted and
 *       behaviour is byte-identical to the pre-LDAP build.</li>
 *   <li>{@code MASK_AUTH_TOKEN_TTL} — ISO-8601 duration, default PT8H.</li>
 *   <li>{@code MASK_AUTH_LDAP_URL} + {@code MASK_AUTH_LDAP_BASE_DN} — both
 *       required to enable login ({@code ldap://} or {@code ldaps://}).</li>
 *   <li>{@code MASK_AUTH_LDAP_BIND_DN} / {@code MASK_AUTH_LDAP_BIND_PASSWORD} —
 *       optional service account for the user search; anonymous search is
 *       used when unset.</li>
 *   <li>{@code MASK_AUTH_LDAP_USER_SEARCH_BASE} — default the base DN.</li>
 *   <li>{@code MASK_AUTH_LDAP_USER_FILTER} — default {@code (uid={0})};
 *       AD deployments use {@code (sAMAccountName={0})}.</li>
 *   <li>{@code MASK_AUTH_LDAP_GROUP_SEARCH_BASE} — set to switch group
 *       resolution to a group search; unset reads the user entry's
 *       {@code memberOf} attribute (AD style).</li>
 *   <li>{@code MASK_AUTH_LDAP_GROUP_FILTER} — default {@code (member={dn})},
 *       group-search mode only.</li>
 *   <li>{@code MASK_AUTH_ADMIN_GROUPS} / {@code MASK_AUTH_AUDITOR_GROUPS} —
 *       comma-separated group names mapping to roles.</li>
 *   <li>{@code MASK_AUTH_LDAP_CONNECT_TIMEOUT} / {@code ..._RESPONSE_TIMEOUT}
 *       — ISO-8601 durations, default PT3S / PT5S.</li>
 * </ul>
 */
public final class AuthConfig {

  private final String secret;
  private final Duration tokenTtl;
  private final String ldapUrl;
  private final String ldapBaseDn;
  private final String ldapBindDn;
  private final String ldapBindPassword;
  private final String userSearchBase;
  private final String userFilter;
  private final String groupSearchBase;
  private final String groupFilter;
  private final Set<String> adminGroups;
  private final Set<String> auditorGroups;
  private final Duration connectTimeout;
  private final Duration responseTimeout;

  private AuthConfig(Builder builder) {
    this.secret = builder.secret;
    this.tokenTtl = builder.tokenTtl;
    this.ldapUrl = builder.ldapUrl;
    this.ldapBaseDn = builder.ldapBaseDn;
    this.ldapBindDn = builder.ldapBindDn;
    this.ldapBindPassword = builder.ldapBindPassword;
    this.userSearchBase = builder.userSearchBase;
    this.userFilter = builder.userFilter;
    this.groupSearchBase = builder.groupSearchBase;
    this.groupFilter = builder.groupFilter;
    this.adminGroups = builder.adminGroups;
    this.auditorGroups = builder.auditorGroups;
    this.connectTimeout = builder.connectTimeout;
    this.responseTimeout = builder.responseTimeout;
  }

  public static AuthConfig fromEnv() {
    return fromEnv(System.getenv());
  }

  /** Programmatic construction (tests, embedders); the map plays the role of the environment. */
  public static AuthConfig fromEnv(Map<String, String> env) {
    Builder b = new Builder();
    b.secret = env.get("MASK_AUTH_SECRET");
    b.tokenTtl = duration(env.get("MASK_AUTH_TOKEN_TTL"), Duration.ofHours(8), "MASK_AUTH_TOKEN_TTL");
    b.ldapUrl = env.get("MASK_AUTH_LDAP_URL");
    b.ldapBaseDn = env.get("MASK_AUTH_LDAP_BASE_DN");
    b.ldapBindDn = env.get("MASK_AUTH_LDAP_BIND_DN");
    b.ldapBindPassword = env.get("MASK_AUTH_LDAP_BIND_PASSWORD");
    b.userSearchBase = env.get("MASK_AUTH_LDAP_USER_SEARCH_BASE");
    b.userFilter = orDefault(env.get("MASK_AUTH_LDAP_USER_FILTER"), "(uid={0})");
    b.groupSearchBase = env.get("MASK_AUTH_LDAP_GROUP_SEARCH_BASE");
    b.groupFilter = orDefault(env.get("MASK_AUTH_LDAP_GROUP_FILTER"), "(member={dn})");
    b.adminGroups = split(env.get("MASK_AUTH_ADMIN_GROUPS"));
    b.auditorGroups = split(env.get("MASK_AUTH_AUDITOR_GROUPS"));
    b.connectTimeout = duration(env.get("MASK_AUTH_LDAP_CONNECT_TIMEOUT"), Duration.ofSeconds(3),
        "MASK_AUTH_LDAP_CONNECT_TIMEOUT");
    b.responseTimeout = duration(env.get("MASK_AUTH_LDAP_RESPONSE_TIMEOUT"), Duration.ofSeconds(5),
        "MASK_AUTH_LDAP_RESPONSE_TIMEOUT");
    return new AuthConfig(b);
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Duration duration(String value, Duration fallback, String name) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      Duration parsed = Duration.parse(value);
      if (parsed.isZero() || parsed.isNegative()) {
        throw new IllegalArgumentException("must be positive");
      }
      return parsed;
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
      throw new AuthException(AuthException.Code.CONFIG_ERROR,
          name + " is not a positive ISO-8601 duration: " + value);
    }
  }

  private static Set<String> split(String commaSeparated) {
    if (commaSeparated == null || commaSeparated.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(commaSeparated.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  /** Token verification armed; false ⇒ this build behaves exactly like pre-LDAP. */
  public boolean tokenEnabled() {
    return secret != null && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
  }

  /** Login endpoint armed (URL + base DN present). */
  public boolean ldapEnabled() {
    return ldapUrl != null && !ldapUrl.isBlank()
        && ldapBaseDn != null && !ldapBaseDn.isBlank();
  }

  public String secret() {
    return secret;
  }

  public Duration tokenTtl() {
    return tokenTtl;
  }

  public String ldapUrl() {
    return ldapUrl;
  }

  public String ldapBaseDn() {
    return ldapBaseDn;
  }

  public String ldapBindDn() {
    return ldapBindDn;
  }

  public String ldapBindPassword() {
    return ldapBindPassword;
  }

  public String userSearchBase() {
    return orDefault(userSearchBase, ldapBaseDn);
  }

  public String userFilter() {
    return userFilter;
  }

  /** Blank ⇒ resolve groups from the user entry's memberOf attribute. */
  public String groupSearchBase() {
    return groupSearchBase == null ? "" : groupSearchBase;
  }

  public String groupFilter() {
    return groupFilter;
  }

  public Set<String> adminGroups() {
    return adminGroups;
  }

  public Set<String> auditorGroups() {
    return auditorGroups;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration responseTimeout() {
    return responseTimeout;
  }

  Role mapRole(java.util.Collection<String> groups) {
    for (String group : groups) {
      if (adminGroups.contains(group.toLowerCase(Locale.ROOT))) {
        return Role.ADMIN;
      }
    }
    for (String group : groups) {
      if (auditorGroups.contains(group.toLowerCase(Locale.ROOT))) {
        return Role.AUDITOR;
      }
    }
    return Role.USER;
  }

  private static final class Builder {
    String secret;
    Duration tokenTtl;
    String ldapUrl;
    String ldapBaseDn;
    String ldapBindDn;
    String ldapBindPassword;
    String userSearchBase;
    String userFilter;
    String groupSearchBase;
    String groupFilter;
    Set<String> adminGroups = new HashSet<>();
    Set<String> auditorGroups = new HashSet<>();
    Duration connectTimeout;
    Duration responseTimeout;
  }
}
