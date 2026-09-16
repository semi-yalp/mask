package io.sqlmask.policy.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobMatcherTest {

  @Test
  void patternWithoutStarIsExactMatch() {
    assertTrue(GlobMatcher.matches("orders", "orders"));
    assertFalse(GlobMatcher.matches("orders", "order"));
    assertFalse(GlobMatcher.matches("orders", "orders_v2"));
  }

  @Test
  void bareStarMatchesAnyValue() {
    assertTrue(GlobMatcher.matches("*", "orders"));
    assertTrue(GlobMatcher.matches("*", "a"));
  }

  @Test
  void trailingStarMatchesPrefix() {
    assertTrue(GlobMatcher.matches("order_*", "order_2026"));
    assertFalse(GlobMatcher.matches("order_*", "orders_2026"));
    assertFalse(GlobMatcher.matches("order_*", "refund_2026"));
  }

  @Test
  void leadingStarMatchesSuffix() {
    assertTrue(GlobMatcher.matches("*_bak", "orders_bak"));
    assertFalse(GlobMatcher.matches("*_bak", "orders_backup"));
  }

  @Test
  void starInMiddleMatchesAnythingBetween() {
    assertTrue(GlobMatcher.matches("log_*_v2", "log_app_v2"));
    assertTrue(GlobMatcher.matches("log_*_v2", "log__v2"));
    assertFalse(GlobMatcher.matches("log_*_v2", "log_app_v1"));
    assertFalse(GlobMatcher.matches("log_*_v2", "log_app_v2_extra"));
  }

  @Test
  void multipleStarsMatchSegmentsInOrder() {
    assertTrue(GlobMatcher.matches("a*b*c", "axxbyyc"));
    assertTrue(GlobMatcher.matches("a*b*c", "abc"));
    assertFalse(GlobMatcher.matches("a*b*c", "acb"));
    assertFalse(GlobMatcher.matches("a*b*c", "axxbyyb"));
    assertFalse(GlobMatcher.matches("a*b*c", "axxcybb"));
  }

  @Test
  void starMatchesEmptyRemainder() {
    assertTrue(GlobMatcher.matches("order_*", "order_"));
    assertTrue(GlobMatcher.matches("*_x", "_x"));
  }

  @Test
  void consecutiveStarsBehaveAsOneWildcard() {
    assertTrue(GlobMatcher.matches("a**b", "axxb"));
    assertTrue(GlobMatcher.matches("**", "anything"));
  }

  @Test
  void regexMetacharactersAreMatchedLiterally() {
    assertTrue(GlobMatcher.matches("col.name*", "col.name1"));
    assertFalse(GlobMatcher.matches("col.name*", "colxname1"));
    assertFalse(GlobMatcher.matches("a+*", "a"));
  }

  @Test
  void prefixAndSuffixMustNotOverlap() {
    assertFalse(GlobMatcher.matches("ab*a", "ab"));
  }
}
