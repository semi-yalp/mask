package io.sqlmask.policyserver.udf;

import io.sqlmask.error.SqlMaskException;

/**
 * Resolves an engine password by environment variable reference for the UDF
 * center's import/deploy/resync calls. The reference must name a
 * {@code SQLMASK_}-prefixed variable — without the prefix whitelist any
 * environment variable (cloud credentials included) could be aimed at a
 * hostile database during an authentication handshake. Same rule as
 * mask-metadata's {@code EnvCredentialResolver}; the reference (the variable
 * name) may appear in errors, the resolved value never leaves the call chain
 * and is never logged.
 */
public final class EnvPassword {

  public static final String REQUIRED_PREFIX = "SQLMASK_";

  private EnvPassword() {
  }

  /** Prefix-checks the reference, then reads the variable; failures are credential errors. */
  public static String resolve(String passwordRef) {
    if (passwordRef == null || !passwordRef.startsWith(REQUIRED_PREFIX)) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE,
          "passwordRef must reference a " + REQUIRED_PREFIX
              + "* environment variable (got '" + passwordRef + "')");
    }
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set");
    }
    return value;
  }
}
