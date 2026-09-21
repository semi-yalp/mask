package io.sqlmask.policyserver.connection;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.policyserver.model.ConnectionConfig;

import java.util.function.Function;

/**
 * Maps a {@link ConnectionConfig} (which stores a {@code passwordRef}, an
 * environment-variable name) to mask-core's {@link ConnectionSpec} (which needs
 * the resolved password). Password resolution goes through an injectable env
 * lookup so tests never touch the real process environment; a missing/blank
 * env entry fails {@code CONNECTION_FAILED} rather than silently connecting
 * with a bogus password.
 */
public final class ConnectionResolver {

  private static final int DEFAULT_TIMEOUT_SECONDS = 15;

  private final Function<String, String> env;

  public ConnectionResolver() {
    this(System::getenv);
  }

  public ConnectionResolver(Function<String, String> env) {
    this.env = env;
  }

  public ConnectionSpec toSpec(ConnectionConfig cfg) {
    return new ConnectionSpec(cfg.dialect(), cfg.host(), cfg.port(), cfg.database(), cfg.dbUser(),
        resolvePassword(cfg), cfg.schemas(), cfg.includeViews(), false, cfg.sslmode(),
        cfg.connectTimeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : cfg.connectTimeoutSeconds());
  }

  public String resolvePassword(ConnectionConfig cfg) {
    String value = env.apply(cfg.passwordRef());
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONNECTION_FAILED,
          "passwordRef env '" + cfg.passwordRef() + "' is not set for connection to "
              + cfg.host() + ":" + cfg.port() + "/" + cfg.database());
    }
    return value;
  }
}