package io.sqlmask.policyserver.model;

import java.time.Instant;

/**
 * One immutable entry of a policy's version history: monotonically increasing
 * version number, why it was created, the full policy content snapshot, the
 * version a rollback restored from (null for CREATE/UPDATE), and the creation
 * time. Rows are never mutated or deleted once written.
 */
public record PolicyVersion(int version, ChangeType changeType, PolicyEntity content,
    Integer sourceVersion, Instant createdAt) {
}