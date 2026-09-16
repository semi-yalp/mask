package io.sqlmask.policy.match;

/**
 * Glob matching for resource identifier patterns: "*" is the only metacharacter
 * and matches any character sequence within one level (including the empty
 * sequence); every other character, including regex metacharacters, is literal.
 * Both sides are expected pre-normalized (case-folded), so a pattern never
 * expresses an identifier containing a literal "*".
 */
public final class GlobMatcher {

  private GlobMatcher() {
  }

  public static boolean matches(String pattern, String value) {
    int firstStar = pattern.indexOf('*');
    if (firstStar < 0) {
      return pattern.equals(value);
    }
    int lastStar = pattern.lastIndexOf('*');
    if (!value.regionMatches(0, pattern, 0, firstStar)) {
      return false;
    }
    int suffixLen = pattern.length() - lastStar - 1;
    int middleEnd = value.length() - suffixLen;
    if (middleEnd < firstStar) {
      return false;
    }
    if (!value.regionMatches(middleEnd, pattern, lastStar + 1, suffixLen)) {
      return false;
    }
    int valuePos = firstStar;
    int patternPos = firstStar;
    while (patternPos < lastStar) {
      int nextStar = pattern.indexOf('*', patternPos + 1);
      int segStart = patternPos + 1;
      int segLen = nextStar - segStart;
      int found = indexOfSegment(value, pattern, segStart, segLen, valuePos, middleEnd);
      if (found < 0) {
        return false;
      }
      valuePos = found + segLen;
      patternPos = nextStar;
    }
    return true;
  }

  private static int indexOfSegment(String value, String pattern, int segStart, int segLen,
      int from, int to) {
    for (int i = from; i + segLen <= to; i++) {
      if (value.regionMatches(i, pattern, segStart, segLen)) {
        return i;
      }
    }
    return -1;
  }
}
