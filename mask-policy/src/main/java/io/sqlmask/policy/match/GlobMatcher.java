package io.sqlmask.policy.match;

/**
 * Glob matching for resource identifier patterns: "*" matches any character
 * sequence (possibly empty), "?" matches exactly one character, and every other
 * character — including regex metacharacters such as '.' and '+' — is literal.
 * Both sides are expected pre-normalized (case-folded), so a pattern never
 * expresses an identifier containing a literal "*" or "?".
 */
public final class GlobMatcher {

  private GlobMatcher() {
  }

  public static boolean matches(String pattern, String value) {
    int i = 0;
    int j = 0;
    int star = -1;
    int mark = 0;
    while (j < value.length()) {
      if (i < pattern.length() && pattern.charAt(i) == '*') {
        star = i++;
        mark = j;
      } else if (i < pattern.length()
          && (pattern.charAt(i) == '?' || pattern.charAt(i) == value.charAt(j))) {
        i++;
        j++;
      } else if (star >= 0) {
        // Backtrack the last '*' to consume one more value character.
        i = star + 1;
        j = ++mark;
      } else {
        return false;
      }
    }
    // A trailing '*' takes any remainder; j is already at the end.
    while (i < pattern.length() && pattern.charAt(i) == '*') {
      i++;
    }
    return i == pattern.length();
  }
}