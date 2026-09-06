package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.Set;

/**
 * Subject predicate declared by a policy item. "*" is the only wildcard.
 * Specificity: exact user (3) > exact group (2) > "*" (1) > no match (0).
 */
public record SubjectSelector(Set<String> users, Set<String> groups) {

  public SubjectSelector {
    users = users == null ? Set.of() : Set.copyOf(users);
    groups = groups == null ? Set.of() : Set.copyOf(groups);
    if (users.isEmpty() && groups.isEmpty()) {
      throw new PolicyException(
          "subject selector requires a non-empty users or groups list; use [\"*\"] for everyone");
    }
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
