package io.sqlmask.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One authorization rule: a servlet-path prefix, the HTTP methods it covers
 * (empty = every method) and the minimum {@link Role}. Matching mirrors the
 * repo's existing filters — {@link jakarta.servlet.http.HttpServletRequest#getServletPath()}
 * (decoded, context-path-free) with exact-or-trailing-slash prefix compare —
 * so the gate and the container's URL-pattern mapping cannot disagree.
 */
public final class AuthRule {

  final String prefix;
  final Set<String> methods;
  final Role minRole;

  private AuthRule(String prefix, Set<String> methods, Role minRole) {
    this.prefix = prefix;
    this.methods = methods;
    this.minRole = minRole;
  }

  boolean matches(String path, String method) {
    boolean pathMatches = path.equals(prefix) || path.startsWith(prefix + "/");
    if (!pathMatches) {
      return false;
    }
    return methods.isEmpty() || methods.contains(method.toUpperCase(java.util.Locale.ROOT));
  }

  public static final class Builder {

    private final List<AuthRule> rules = new ArrayList<>();

    /** Every method under the prefix requires at least {@code minRole}. */
    public Builder prefix(String prefix, Role minRole) {
      rules.add(new AuthRule(prefix, Set.of(), minRole));
      return this;
    }

    /** Read methods (GET/HEAD/OPTIONS) need {@code readRole}; everything else
     * (POST/PUT/DELETE/PATCH) needs {@code writeRole}. */
    public Builder readOnly(String prefix, Role readRole, Role writeRole) {
      rules.add(new AuthRule(prefix, Set.of("GET", "HEAD", "OPTIONS"), readRole));
      rules.add(new AuthRule(prefix, Set.of("POST", "PUT", "DELETE", "PATCH"), writeRole));
      return this;
    }

    public List<AuthRule> build() {
      return List.copyOf(rules);
    }
  }
}
