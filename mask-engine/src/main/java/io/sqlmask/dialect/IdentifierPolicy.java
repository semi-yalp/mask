package io.sqlmask.dialect;

/**
 * Renders identifiers of the generated outer projection in one dialect's
 * rules (quoting style, reserved words). The inner query is the user's
 * original text and is never re-rendered.
 */
public interface IdentifierPolicy {

  /** Renders a single identifier. */
  String render(String name);

  /** Renders {@code alias.name}. */
  default String renderQualified(String alias, String name) {
    return render(alias) + "." + render(name);
  }
}
