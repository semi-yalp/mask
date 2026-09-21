package io.sqlmask.policyserver.model;

import java.util.List;

/**
 * Connection details of one engine instance. Mirrors mask-core's
 * {@code io.sqlmask.introspect.ConnectionSpec} but stores a
 * {@code passwordRef} (an environment-variable name) instead of a plaintext
 * password, per the repo-wide secrets convention. {@code schema}{@code s} are
 * catalogue filters; {@code sslmode} defaults to {@code disable}.
 */
public record ConnectionConfig(String dialect, String host, int port, String database,
    String dbUser, String passwordRef, List<String> schemas, boolean includeViews,
    String sslmode, Integer connectTimeoutSeconds) {

  public ConnectionConfig {
    schemas = schemas == null ? List.of() : List.copyOf(schemas);
    sslmode = sslmode == null || sslmode.isBlank() ? "disable" : sslmode;
  }
}