package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;
import org.springframework.stereotype.Component;

/** Resolves the instance's passwordRef from the process environment — the
 * same convention as mask-metadata's EnvCredentialResolver. The value never
 * appears in errors or logs. */
@Component
public class Credentials implements CredentialSource {

  @Override
  public String resolve(String passwordRef) {
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new QueryException(QueryException.CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set; query aborted");
    }
    return value;
  }
}
