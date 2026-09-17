package io.sqlmask.query.service;

/** Password source of the execution pipeline; tests stub it. Its own file so
 * it can be public — the query path and the controller tests both need it. */
public interface CredentialSource {

  String resolve(String passwordRef);
}
