package io.sqlmask.dialect;

import io.sqlmask.metadata.TableMetadata;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses one engine's type declaration into a Calcite type descriptor. */
public interface TypeResolver {

  TableMetadata.Column parseColumn(String name, String typeDeclaration);

  /** Base type name plus optional {@code (precision[, scale])} parameters. */
  record ParsedType(String base, Integer precision, Integer scale) {
  }

  Pattern PARAMS =
      Pattern.compile("^(.+?)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");

  /**
   * Splits an already-lowercased declaration: strips the optional trailing
   * {@code suffix} (e.g. {@code " with time zone"}), then matches
   * {@code base(p[, s])}. Returns null when the suffix is required but absent.
   */
  static ParsedType split(String lowered, String suffix) {
    String rest = lowered;
    if (suffix != null) {
      if (!lowered.endsWith(suffix)) {
        return null;
      }
      rest = lowered.substring(0, lowered.length() - suffix.length()).trim();
    }
    Matcher matcher = PARAMS.matcher(rest);
    if (matcher.matches()) {
      Integer precision = Integer.valueOf(matcher.group(2));
      Integer scale = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
      return new ParsedType(matcher.group(1).trim(), precision, scale);
    }
    return new ParsedType(rest, null, null);
  }
}
