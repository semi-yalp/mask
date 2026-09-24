package io.sqlmask.introspect.udf;

import io.sqlmask.introspect.ConnectionSpec;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real pg_proc UDF introspection against an embedded PostgreSQL: overload
 * grouping, the DEFAULT-parameter function and the read-only
 * {@code introspect(ConnectionSpec)} entry point — the paths a mocked
 * Connection cannot reach. Same skip switch as
 * {@code PgMetadataIntrospectorRealTest} ({@code MASK_SKIP_REAL_PG=true}).
 */
@DisabledIfEnvironmentVariable(named = "MASK_SKIP_REAL_PG", matches = "(?i)true|1|on")
class PgUdfIntrospectorRealTest {

  private static EmbeddedPostgres embedded;

  @BeforeAll
  static void startDatabase() throws IOException {
    embedded = EmbeddedPostgres.builder().start();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        embedded.getJdbcUrl("postgres", "postgres")));
    jdbc.execute("""
        CREATE FUNCTION mask_phone(v text, front integer, back integer)
        RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
          SELECT substr(v, 1, front) || substr(v, length(v) - back + 1, back)
        $$""");
    jdbc.execute("""
        CREATE FUNCTION mask_idcard(v text, keep integer DEFAULT 4)
        RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
          SELECT substr(v, 1, 6) || substr(v, length(v) - keep + 1, keep)
        $$""");
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    if (embedded != null) {
      embedded.close();
    }
  }

  private static ConnectionSpec spec(List<String> schemas) {
    return new ConnectionSpec("postgresql", "127.0.0.1", embedded.getPort(), "postgres",
        "postgres", "", schemas, false, false, "disable", 10);
  }

  @Test
  void discoversSignaturesIncludingDefaultParameterFunction() {
    List<UdfIntrospector.UdfSignature> signatures =
        new PgUdfIntrospector().introspect(spec(List.of("public")));
    // the embedded cluster's public schema only carries what this test created
    assertEquals(2, signatures.size(), () -> signatures.toString());
    UdfIntrospector.UdfSignature phone = signatures.stream()
        .filter(s -> s.name().equals("mask_phone")).findFirst().orElseThrow();
    assertEquals(List.of("text", "integer", "integer"), phone.paramTypes());
    assertEquals("text", phone.returnType());
    // identity arguments drop the DEFAULT clause: keep surfaces as plain integer
    UdfIntrospector.UdfSignature idcard = signatures.stream()
        .filter(s -> s.name().equals("mask_idcard")).findFirst().orElseThrow();
    assertEquals(List.of("text", "integer"), idcard.paramTypes());
    assertEquals("text", idcard.returnType());
  }

  @Test
  void emptySchemaListFallsBackToPublic() {
    List<UdfIntrospector.UdfSignature> signatures =
        new PgUdfIntrospector().introspect(spec(List.of()));
    assertTrue(signatures.stream().anyMatch(s -> s.name().equals("mask_phone")),
        () -> signatures.toString());
  }

  @Test
  void otherSchemaYieldsNothing() {
    List<UdfIntrospector.UdfSignature> signatures =
        new PgUdfIntrospector().introspect(spec(List.of("no_such_schema")));
    assertTrue(signatures.isEmpty());
  }
}
