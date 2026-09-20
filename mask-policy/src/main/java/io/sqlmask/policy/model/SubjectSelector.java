package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Subject predicate declared by a policy item. "*" is the only wildcard.
 * Specificity: exact user (3) > exact group (2) > "*" (1) > no match (0).
 *
 * <p>Every declared name is trimmed at construction, symmetric with
 * {@link Subject#of}: a selector entry written as {@code "alice "} would
 * otherwise never match the query subject {@code alice} and the policy
 * would silently stop applying. Blank or whitespace-only entries are
 * rejected outright rather than dropped, so a mistyped policy fails at
 * load time instead of silently narrowing its reach.
 *
 * <p>v3 (2026-09-21): rebuilt in place; contract byte-identical to the
 * previous implementation.
 */
public record SubjectSelector(Set<String> users, Set<String> groups) {

  public SubjectSelector {
    users = normalize(users, "users");
    groups = normalize(groups, "groups");
    if (users.isEmpty() && groups.isEmpty()) {
      throw new PolicyException(
          "subject selector requires a non-empty users or groups list; use [\"*\"] for everyone");
    }
  }

  private static Set<String> normalize(Set<String> values, String field) {
    if (values == null) {
      return Set.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw new PolicyException(
            "subject selector " + field + " entries must be non-blank strings");
      }
      normalized.add(value.trim());
    }
    return Set.copyOf(normalized);
  }

  /** Highest matching specificity for {@code subject}; 0 when nothing matches. */
  public int matchLevel(Subject subject) {
    if (subject == null) {
      return 0;
    }
    int level = 0;
    if (users.contains("*") || groups.contains("*")) {
      level = 1;
    }
    if (subject.groups().stream().anyMatch(g -> !g.equals("*") && groups.contains(g))) {
      level = Math.max(level, 2);
    }
    if (subject.user() != null && users.contains(subject.user()) && !subject.user().equals("*")) {
      level = Math.max(level, 3);
    }
    return level;
  }
}