package io.sqlmask.policy.model;

import java.util.List;

/**
 * Query subject: the acting user and their groups. A null user with no groups
 * is the anonymous subject; it matches only "*" wildcard selectors.
 */
public record Subject(String user, List<String> groups) {

  public Subject {
    groups = groups == null ? List.of() : List.copyOf(groups);
  }

  public static Subject anonymous() {
    return new Subject(null, List.of());
  }

  /** Normalizes blanks away, trims and de-duplicates groups preserving order. */
  public static Subject of(String user, List<String> groups) {
    String normalizedUser = user == null || user.isBlank() ? null : user.trim();
    List<String> normalizedGroups = groups == null ? List.of() : groups.stream()
        .filter(g -> g != null && !g.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
    return new Subject(normalizedUser, normalizedGroups);
  }
}
