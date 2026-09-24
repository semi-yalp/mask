package io.sqlmask.introspect.udf;

import io.sqlmask.introspect.ConnectionSpec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL UDF discovery: one read-only query against {@code pg_proc} joined
 * with {@code pg_namespace}, restricted to ordinary functions
 * ({@code prokind = 'f'} — aggregates {@code 'a'} and window functions
 * {@code 'w'} are not masking UDFs) of one schema.
 *
 * <p>{@code pg_get_function_identity_arguments} renders each argument as
 * {@code "name type"} when the function was declared with parameter names
 * ({@code "v text, front integer"}), bare types otherwise; defaults are not
 * included. {@link #parsePgArgs(String)} normalizes both renderings: it splits
 * on top-level commas only (so {@code numeric(10,2)} survives), strips a
 * {@code DEFAULT ...} suffix defensively (present only in the richer
 * {@code pg_get_function_arguments} rendering), and drops a leading parameter
 * name unless the segment starts with a multi-word built-in type
 * ({@code double precision}, {@code character varying},
 * {@code timestamp(t) with time zone}, … — PostgreSQL type names cannot
 * contain spaces, so only that closed set is ambiguous with a name).</p>
 */
public class PgUdfIntrospector implements UdfIntrospector {

  private static final String FUNCTIONS_SQL = """
      SELECT p.proname AS name, pg_get_function_identity_arguments(p.oid) AS args,
             format_type(p.prorettype, NULL) AS ret
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = ? AND p.prokind = 'f'
      ORDER BY p.proname""";

  @Override
  public List<UdfSignature> introspect(Connection connection, String schema) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(FUNCTIONS_SQL)) {
      statement.setString(1, schema);
      try (ResultSet rs = statement.executeQuery()) {
        List<UdfSignature> signatures = new ArrayList<>();
        while (rs.next()) {
          signatures.add(new UdfSignature(rs.getString("name"),
              parsePgArgs(rs.getString("args")), rs.getString("ret")));
        }
        return signatures;
      }
    }
  }

  /** Masking templates deploy into {@code public}; the search path is not consulted. */
  @Override
  public String fallbackTarget(ConnectionSpec spec) {
    return "public";
  }

  /**
   * Parses a {@code pg_get_function_identity_arguments} rendering into the
   * ordered parameter types: empty/null input means no parameters; segments
   * split on top-level commas only ({@code numeric(10,2)} stays whole), each
   * has a trailing {@code DEFAULT ...} clause removed (case-insensitive,
   * outside parentheses) and a leading parameter name dropped.
   */
  static List<String> parsePgArgs(String args) {
    if (args == null || args.isBlank()) {
      return List.of();
    }
    List<String> types = new ArrayList<>();
    StringBuilder segment = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      }
      if (c == ',' && depth == 0) {
        types.add(stripParamName(stripDefault(segment.toString())));
        segment.setLength(0);
      } else {
        segment.append(c);
      }
    }
    types.add(stripParamName(stripDefault(segment.toString())));
    return types;
  }

  /**
   * First tokens of PostgreSQL's multi-word built-in types. A segment
   * starting with one of these is a bare type ({@code double precision},
   * {@code timestamp(3) with time zone}); any other head token before a
   * top-level space is a parameter name to drop.
   */
  private static final java.util.Set<String> MULTIWORD_TYPE_HEADS =
      java.util.Set.of("character", "bit", "double", "timestamp", "time", "interval");

  /** Drops a leading parameter name; multi-word type heads are kept whole. */
  private static String stripParamName(String segment) {
    String trimmed = segment.trim();
    int depth = 0;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0 && Character.isWhitespace(c)) {
        String head = trimmed.substring(0, i).trim().toLowerCase(java.util.Locale.ROOT);
        return MULTIWORD_TYPE_HEADS.contains(head)
            ? trimmed
            : trimmed.substring(i).trim();
      }
    }
    return trimmed;
  }

  /** Removes a trailing {@code DEFAULT ...} clause and trims whitespace. */
  private static String stripDefault(String segment) {
    String trimmed = segment.trim();
    int depth = 0;
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth = Math.max(0, depth - 1);
      } else if (depth == 0 && i > 0 && Character.isWhitespace(c)
          && trimmed.regionMatches(true, i + 1, "DEFAULT", 0, 7)) {
        int after = i + 8;
        if (after >= trimmed.length() || Character.isWhitespace(trimmed.charAt(after))) {
          return trimmed.substring(0, i).trim();
        }
      }
    }
    return trimmed;
  }
}
