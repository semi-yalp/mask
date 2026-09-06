package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import org.springframework.stereotype.Service;

/**
 * Resolves a password by environment variable reference. The reference (the
 * variable name) may appear in errors; the resolved value never leaves this
 * call chain and is never logged.
 */
@Service
public class EnvCredentialResolver implements CredentialResolver {

  @Override
  public String resolve(String passwordRef) {
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set; collection aborted");
    }
    return value;
  }
}
