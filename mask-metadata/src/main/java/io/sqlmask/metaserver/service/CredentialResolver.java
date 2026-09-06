package io.sqlmask.metaserver.service;

/** Resolves a stored password reference into the credential used for collection. */
@FunctionalInterface
public interface CredentialResolver {

  String resolve(String passwordRef);
}
