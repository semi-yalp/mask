package io.sqlmask.server.auth;

import io.sqlmask.auth.AuthConfig;
import io.sqlmask.auth.AuthMode;
import io.sqlmask.auth.AuthTokenService;
import io.sqlmask.auth.LdapAuthenticator;
import io.sqlmask.auth.SimpleUserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Central, optional authentication wiring. {@code mask.auth.mode=none} (the
 * default) registers nothing — every surface is open. {@code simple} arms
 * bearer tokens backed by the local {@code auth_user} table; {@code ldap}
 * delegates to the corporate directory (MASK_AUTH_* variables). One bearer
 * gate covers every domain surface, so the per-service gates of the split
 * era are gone.
 */
@Configuration
public class AuthModeConfig {

  private static final Logger log = LoggerFactory.getLogger(AuthModeConfig.class);

  @Bean
  AuthMode authMode(@Value("${mask.auth.mode:none}") String mode) {
    AuthMode parsed = AuthMode.parse(mode);
    if (!mode.isBlank() && parsed == AuthMode.NONE && !"none".equalsIgnoreCase(mode.trim())) {
      log.warn("mask.auth.mode='{}' is not a known mode (none|simple|ldap); falling back to none",
          mode);
    }
    return parsed;
  }

  @Bean
  AuthConfig authConfig() {
    return AuthConfig.fromEnv();
  }

  /** The secret used to sign bearer tokens. LDAP mode requires the operator
   * to provide MASK_AUTH_SECRET; simple mode may fall back to an ephemeral
   * random secret (tokens then do not survive a restart, which is a safe
   * default for local runs). */
  @Bean(name = "authSigningSecret")
  String authSigningSecret(AuthMode mode, AuthConfig config) {
    if (mode == AuthMode.NONE) {
      return "";
    }
    if (config.secret() != null && !config.secret().isBlank()) {
      return config.secret();
    }
    if (mode == AuthMode.LDAP) {
      // the shared-secret contract of the LDAP mode stays explicit: silent
      // ephemeral secrets would invalidate tokens on every restart of a
      // multi-service deployment
      log.warn("mask.auth.mode=ldap without MASK_AUTH_SECRET: bearer tokens stay disabled");
      return "";
    }
    byte[] random = new byte[48];
    new SecureRandom().nextBytes(random);
    log.warn("simple auth mode without MASK_AUTH_SECRET: using an EPHEMERAL signing secret "
        + "(tokens are invalidated on every restart)");
    return Base64.getEncoder().encodeToString(random);
  }

  @Bean
  AuthTokenService authTokenService(AuthMode mode, AuthConfig config,
                                    ObjectProvider<String> secret) {
    String signing = secret.getIfAvailable();
    if (mode == AuthMode.NONE || signing == null || signing.isBlank()) {
      return null;
    }
    return new AuthTokenService(signing, config.tokenTtl());
  }

  @Bean
  LdapAuthenticator ldapAuthenticator(AuthMode mode, AuthConfig config) {
    return mode == AuthMode.LDAP && config.ldapEnabled()
        ? new LdapAuthenticator(config)
        : null;
  }

  @Bean
  SimpleUserStore simpleUserStore(AuthMode mode, ObjectProvider<JdbcTemplate> jdbc) {
    if (mode != AuthMode.SIMPLE) {
      return null;
    }
    JdbcTemplate template = jdbc.getIfAvailable();
    if (template == null) {
      throw new IllegalStateException("simple auth mode requires the shared datasource");
    }
    return new JdbcSimpleUserStore(template);
  }

  /** The one bearer gate. Rules cover every domain surface with the same
   * read=USER / write=ADMIN split the standalone services used; unmatched
   * paths (notably /api/auth/login and /api/auth/mode) stay open so login
   * itself is reachable. */
  @Bean
  FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> bearerAuthFilter(
      AuthMode mode, ObjectProvider<AuthTokenService> tokens) {
    FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> off =
        new FilterRegistrationBean<>(
            new io.sqlmask.auth.BearerAuthFilter(null, List.of()));
    if (mode == AuthMode.NONE || tokens.getIfAvailable() == null) {
      off.setEnabled(false);
      return off; // no-auth default: the gate is transparent
    }
    List<io.sqlmask.auth.AuthRule> rules = new io.sqlmask.auth.AuthRule.Builder()
        .readOnly("/api/instances", io.sqlmask.auth.Role.USER, io.sqlmask.auth.Role.ADMIN)
        .readOnly("/api/meta/instances", io.sqlmask.auth.Role.USER, io.sqlmask.auth.Role.ADMIN)
        .prefix("/api/metadata", io.sqlmask.auth.Role.USER)
        .prefix("/api/effective", io.sqlmask.auth.Role.USER)
        .prefix("/api/rewrite", io.sqlmask.auth.Role.USER)
        .prefix("/api/v1", io.sqlmask.auth.Role.USER)
        .prefix("/api/risk", io.sqlmask.auth.Role.USER)
        .prefix("/api/audit", io.sqlmask.auth.Role.AUDITOR)
        .readOnly("/api/auth/users", io.sqlmask.auth.Role.ADMIN, io.sqlmask.auth.Role.ADMIN)
        .prefix("/admin/cache", io.sqlmask.auth.Role.ADMIN)
        .build();
    FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> registration =
        new FilterRegistrationBean<>(
            new io.sqlmask.auth.BearerAuthFilter(tokens.getObject(), rules));
    registration.addUrlPatterns("/api/*", "/admin/*");
    registration.setOrder(0);
    log.info("authentication is ARMED (mode={}): bearer tokens required per surface rules",
        mode);
    return registration;
  }

  /** First start in simple mode: the bootstrap admin. */
  @Bean
  ApplicationRunner adminBootstrap(AuthMode mode, ObjectProvider<SimpleUserStore> users) {
    return args -> {
      SimpleUserStore store = users.getIfAvailable();
      if (mode != AuthMode.SIMPLE || store == null) {
        return;
      }
      if (store.list().isEmpty()) {
        store.create(new io.sqlmask.auth.SimpleUser("admin", "Administrator",
            io.sqlmask.auth.Role.ADMIN, List.of(), io.sqlmask.auth.Passwords.hash("admin"),
            true));
        log.warn("simple auth: created the bootstrap user admin/admin — CHANGE THIS PASSWORD "
            + "(POST /api/auth/login then PUT /api/auth/users/admin)");
      }
    };
  }
}
