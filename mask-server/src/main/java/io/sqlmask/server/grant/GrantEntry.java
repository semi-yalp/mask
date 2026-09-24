package io.sqlmask.server.grant;

import io.sqlmask.error.SqlMaskException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * One authorization entry of the unified (metadata-level) grant model:
 * a principal gets a privilege on a resource of an engine instance. The row
 * is the single source of truth; per-engine GRANT statements are compiled
 * from it on demand (preview first, apply optional), and the query gateway
 * can later enforce it pre-execution.
 *
 * <p>Resources: CATALOG("c"), SCHEMA("c.s"), TABLE("c.s.t"), COLUMN("c.s.t.col")
 * — {@code resourceId} carries the dot-joined path, lower-cased like every
 * policy resource.
 */
public record GrantEntry(long id, String instance, PrincipalType principalType, String principal,
    ResourceType resourceType, String resourceId, Privilege privilege, String grantedBy,
    Instant createdAt) {

  public enum PrincipalType { USER, GROUP }

  public enum ResourceType { CATALOG, SCHEMA, TABLE, COLUMN }

  public enum Privilege { SELECT, INSERT, UPDATE, DELETE, ALL }

  public GrantEntry {
    resourceId = resourceId == null ? null : resourceId.trim().toLowerCase(Locale.ROOT);
  }

  /** Validates shape (not existence): resource path depth must match the type. */
  public void validate() {
    if (principal == null || principal.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "principal is required");
    }
    int depth = resourceId == null || resourceId.isBlank() ? 0 : resourceId.split("\\.").length;
    int required = switch (resourceType) {
      case CATALOG -> 1;
      case SCHEMA -> 2;
      case TABLE -> 3;
      case COLUMN -> 4;
    };
    if (depth != required) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          resourceType + " grant needs a " + required + "-part resource path (got '"
              + resourceId + "')");
    }
  }
}
