package io.sqlmask.policyserver.udf;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in masking-UDF templates for PostgreSQL, ported from
 * {@code deploy/local-e2e/01_udf.sql}. Every template is a single
 * {@code CREATE OR REPLACE FUNCTION} statement, {@code IMMUTABLE STRICT}
 * ({@code STRICT} gives the NULL-in-NULL-out contract for free), so the UDF
 * center can hand the whole text to one {@code Statement.execute} — PostgreSQL
 * JDBC accepts multi-statement strings, and none of these bodies need
 * splitting.
 *
 * <p>Semantics: {@code mask_phone} keeps the first {@code front} and last
 * {@code back} characters (3/4 for the usual CN mobile shape),
 * {@code mask_email} keeps the first character plus the {@code @domain},
 * {@code mask_name} keeps the family name and the final character,
 * {@code mask_idcard} keeps the 6-digit region prefix plus the last
 * {@code keep} digits (4 default), and {@code mask_text} replaces the whole
 * value with {@code ***}.</p>
 */
public final class UdfTemplates {

  private static final Map<String, String> TEMPLATES = Map.of(
      "mask_phone", """
          CREATE OR REPLACE FUNCTION mask_phone(v text, front integer, back integer)
          RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
          DECLARE n integer := length(v);
          BEGIN
            IF n <= front + back THEN
              RETURN repeat('*', n);
            END IF;
            RETURN substr(v, 1, front) || repeat('*', n - front - back) || substr(v, n - back + 1, back);
          END $$;""",

      "mask_email", """
          CREATE OR REPLACE FUNCTION mask_email(v text)
          RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
          DECLARE pos integer := position('@' in v);
          BEGIN
            IF pos = 0 THEN
              RETURN repeat('*', length(v));
            END IF;
            RETURN substr(v, 1, 1) || '***' || substr(v, pos);
          END $$;""",

      "mask_name", """
          CREATE OR REPLACE FUNCTION mask_name(v text)
          RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
          DECLARE n integer := length(v);
          BEGIN
            IF n <= 1 THEN
              RETURN repeat('*', n);
            END IF;
            RETURN substr(v, 1, 1) || repeat('*', n - 2) || substr(v, n, 1);
          END $$;""",

      "mask_idcard", """
          CREATE OR REPLACE FUNCTION mask_idcard(v text, keep integer DEFAULT 4)
          RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT AS $$
          DECLARE n integer := length(v);
          BEGIN
            IF n <= 6 + keep THEN
              RETURN repeat('*', n);
            END IF;
            RETURN substr(v, 1, 6) || repeat('*', n - 6 - keep) || substr(v, n - keep + 1, keep);
          END $$;""",

      "mask_text", """
          CREATE OR REPLACE FUNCTION mask_text(v text)
          RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
            SELECT '***'
          $$;""");

  private UdfTemplates() {
  }

  /** The template DDL by name; empty for an unknown template. */
  public static Optional<String> template(String name) {
    return Optional.ofNullable(TEMPLATES.get(name));
  }

  /** All template names, alphabetically. */
  public static List<String> names() {
    return TEMPLATES.keySet().stream().sorted().toList();
  }
}
