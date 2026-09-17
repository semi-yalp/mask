package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;
import org.springframework.stereotype.Component;

/** Password source of the execution pipeline; tests stub it. Nested in this
 * file because Java allows only one public top-level type per file — every
 * consumer lives in this package. */
interface CredentialSource {

  String resolve(String passwordRef);
}

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
