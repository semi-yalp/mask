package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import org.springframework.stereotype.Service;

/**
 * Resolves a password by environment variable reference. The reference must
 * name a {@code SQLMASK_}-prefixed variable: the collection payload decides
 * which variable to read, and without the prefix whitelist any environment
 * variable (cloud credentials included) could be aimed at a hostile database
 * during an authentication handshake. The reference (the variable name) may
 * appear in errors; the resolved value never leaves this call chain and is
 * never logged.
 */
@Service
public class EnvCredentialResolver implements CredentialResolver {

  public static final String REQUIRED_PREFIX = "SQLMASK_";

  @Override
  public String resolve(String passwordRef) {
    if (passwordRef == null || !passwordRef.startsWith(REQUIRED_PREFIX)) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE,
          "passwordRef must reference a " + REQUIRED_PREFIX
              + "* environment variable (got '" + passwordRef + "'); collection aborted");
    }
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set; collection aborted");
    }
    return value;
  }
}
