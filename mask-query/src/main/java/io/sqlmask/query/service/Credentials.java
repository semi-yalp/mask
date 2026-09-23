package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;
import org.springframework.stereotype.Component;

/** Resolves the instance's passwordRef from the process environment — the
 * same convention as mask-metadata's EnvCredentialResolver, including the
 * {@code SQLMASK_} prefix whitelist: the instance store decides which variable
 * to read, and any other environment variable must stay unreachable. The
 * value never appears in errors or logs. */
@Component
public class Credentials implements CredentialSource {

  /** Same whitelist as mask-metadata's EnvCredentialResolver. */
  static final String REQUIRED_PREFIX = "SQLMASK_";

  @Override
  public String resolve(String passwordRef) {
    if (passwordRef == null || !passwordRef.startsWith(REQUIRED_PREFIX)) {
      throw new QueryException(QueryException.CREDENTIAL_UNAVAILABLE,
          "passwordRef must reference a SQLMASK_* environment variable (got '" + passwordRef
              + "'); query aborted");
    }
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new QueryException(QueryException.CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set; query aborted");
    }
    return value;
  }
}
