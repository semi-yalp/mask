package io.sqlmask.introspect.udf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit coverage of {@link PgUdfIntrospector#parsePgArgs(String)}. */
class PgUdfIntrospectorTest {

  @Test
  void emptyRenderingMeansNoParameters() {
    assertEquals(List.of(), PgUdfIntrospector.parsePgArgs(""));
    assertEquals(List.of(), PgUdfIntrospector.parsePgArgs(null));
    assertEquals(List.of(), PgUdfIntrospector.parsePgArgs("   "));
  }

  @Test
  void bareTypeRenderingStaysVerbatim() {
    assertEquals(List.of("text", "integer", "integer"),
        PgUdfIntrospector.parsePgArgs("text, integer, integer"));
  }

  @Test
  void parameterNamesAreDropped() {
    // modern PG renders identity arguments with the declared names
    assertEquals(List.of("text", "integer", "integer"),
        PgUdfIntrospector.parsePgArgs("v text, front integer, back integer"));
  }

  @Test
  void multiWordTypesAreNotMistakenForNames() {
    assertEquals(List.of("double precision", "timestamp with time zone"),
        PgUdfIntrospector.parsePgArgs("double precision, timestamp with time zone"));
    assertEquals(List.of("double precision"),
        PgUdfIntrospector.parsePgArgs("d double precision"));
    assertEquals(List.of("character varying(20)"),
        PgUdfIntrospector.parsePgArgs("v character varying(20)"));
  }

  @Test
  void commasInsideTypeModifiersDoNotSplit() {
    assertEquals(List.of("numeric(10,2)", "text"),
        PgUdfIntrospector.parsePgArgs("amount numeric(10,2), v text"));
  }

  @Test
  void defaultClausesAreStripped() {
    // defensive: identity rendering has no defaults, but the richer
    // pg_get_function_arguments shape "a varchar, b integer DEFAULT 0" parses too
    assertEquals(List.of("varchar", "integer"),
        PgUdfIntrospector.parsePgArgs("a varchar, b integer DEFAULT 0"));
    assertEquals(List.of("text", "integer"),
        PgUdfIntrospector.parsePgArgs("v text, keep integer default 4"));
    // commas inside a default expression stay inside the segment
    assertEquals(List.of("integer", "text"),
        PgUdfIntrospector.parsePgArgs("n integer DEFAULT least(1,2), v text"));
  }

  @Test
  void defaultLikeIdentifiersAreKept() {
    // "mydefault" must not trip the DEFAULT-clause stripper
    assertEquals(List.of("mydefault(3)"), PgUdfIntrospector.parsePgArgs("mydefault(3)"));
    // a parameter name that merely contains "default" is a name, not a clause
    assertEquals(List.of("text", "text"),
        PgUdfIntrospector.parsePgArgs("default_on text, v text"));
  }
}
