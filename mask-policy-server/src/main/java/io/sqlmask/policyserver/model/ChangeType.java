package io.sqlmask.policyserver.model;

/**
 * Why a {@link PolicyVersion} was created. History is append-only: an update
 * is a new version and a rollback is another new version whose content comes
 * from an earlier one (never an in-place edit or a pointer move).
 */
public enum ChangeType {
  CREATE, UPDATE, ROLLBACK
}