package io.sqlmask.cli;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLI instance mode: config from the policy service instead of --metadata. */
class SqlMaskInstanceModeTest {

  private static final String EFFECTIVE_BODY = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"id","type":"bigint"},
           {"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"mask_phone"}],
         "policies":{"mask_phone":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private static HttpServer stub;

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/api/effective/pg_prod", exchange -> {
      byte[] body = EFFECTIVE_BODY.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @Test
  void rewritesViaPolicyServiceWithoutMetadataFile() {
    CliOptions options = new CliOptions(null, null, null, null,
        "SELECT id, phone FROM customer", null, null, "postgresql",
        "pg_prod", "http://localhost:" + stub.getAddress().getPort());
    String out = new SqlMaskRunner().run(options);
    assertThat(out).contains("mask_phone(r.phone, 3, 4) AS phone");
  }

  @Test
  void missingServiceUrlFailsClosed() {
    Assumptions.assumeTrue(System.getenv("POLICY_SERVICE_URL") == null,
        "POLICY_SERVICE_URL is set on this machine");
    CliOptions options = new CliOptions(null, null, null, null,
        "SELECT 1", null, null, "postgresql", "pg_prod", null);
    assertThatThrownBy(() -> new SqlMaskRunner().run(options))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("policy service");
  }

  @Test
  void instanceCannotBeCombinedWithMetadata() {
    int exit = new SqlMaskApplication().run(
        new String[] {"--instance", "pg_prod", "--metadata", "whatever.yaml",
            "--sql", "SELECT 1"},
        System.in, System.out, System.err);
    assertThat(exit).isEqualTo(2);
  }
}
