package io.sqlmask.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.audit.AuditEvent;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.CredentialSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-pipeline integration test: HTTP entry → InstanceDirectory → stubbed
 * rewrite service → real embedded PostgreSQL (with the real {@code mask_phone}
 * UDF installed) → masked result over the wire. mask-core and mask-metadata
 * are stubbed with JDK HttpServers whose payloads mirror their wire contracts;
 * audit events land in a captured in-memory sink instead of the audit bridge.
 *
 * <p>The {@code common ApiKeyFilter} fails closed with no SQLMASK_QUERY_API_KEY
 * in the process environment, so MockMvc runs with {@code addFilters=false}
 * (same convention as {@code QueryControllerTest}); the filter's own semantics
 * are covered by {@code common ApiKeyFilterTest}.
 */
@SpringBootTest(properties = "query.timeout-seconds=2")
@AutoConfigureMockMvc(addFilters = false)
@Import(QueryEndToEndTest.Stubs.class)
class QueryEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The default plan served by the rewrite stub; every test sets its own. */
  private static final String ACTIVE_ROWS_PLAN =
      "SELECT mask_phone(r.phone, 3, 4) AS phone FROM "
          + "(SELECT id, phone, status FROM public.customer WHERE status = 'active') AS r";

  static EmbeddedPostgres pg;
  static HttpServer metadataStub;   // GET /api/instances/pg_prod → InstanceDetailResponse JSON
  static HttpServer rewriteStub;    // POST /api/rewrite/instances/pg_prod → canned masked SQL
  static final AtomicReference<String> stubsRewrittenSql = new AtomicReference<>("");
  static int metadataPort;
  static int rewritePort;

  @BeforeAll
  static void startAll() throws Exception {
    // 1) embedded PostgreSQL: the customer table, two rows and the real
    //    mask_phone UDF. mask_phone('13812345678', 3, 4) = '138' + '****' + '5678'.
    pg = EmbeddedPostgres.builder().start();
    try (Connection setup = pg.getPostgresDatabase().getConnection();
        Statement ddl = setup.createStatement()) {
      ddl.execute("CREATE TABLE public.customer (id bigint, phone varchar, status varchar)");
      ddl.execute("INSERT INTO public.customer VALUES "
          + "(1, '13812345678', 'active'), (2, '13900001111', 'archived')");
      ddl.execute("CREATE FUNCTION mask_phone(v varchar, keep_first int, keep_last int) "
          + "RETURNS varchar AS $$ SELECT left(v, keep_first) || repeat('*', 4) "
          + "|| right(v, keep_last) $$ LANGUAGE sql");
    }
    stubsRewrittenSql.set(ACTIVE_ROWS_PLAN);

    // 2) metadata stub: instance detail in the mask-metadata wire contract;
    //    engine is null on purpose — mask-query derives it from the dialect.
    ObjectNode connection = JSON.createObjectNode()
        .put("host", "127.0.0.1")
        .put("port", pg.getPort())
        .put("database", "postgres")
        .put("dbUser", "postgres")
        .put("passwordRef", "IT_PG_PASSWORD") // resolved by the stubbed CredentialSource
        .put("sslmode", "disable")
        .put("connectTimeoutSeconds", 5);
    connection.putArray("schemas").add("public");
    connection.put("includeViews", false);
    ObjectNode table = JSON.createObjectNode()
        .put("catalog", "postgres").put("schema", "public").put("name", "customer");
    var columns = table.putArray("columns");
    columns.addObject().put("name", "id").put("type", "bigint");
    columns.addObject().put("name", "phone").put("type", "varchar");
    columns.addObject().put("name", "status").put("type", "varchar");
    ObjectNode instance = JSON.createObjectNode()
        .put("name", "pg_prod").put("dialect", "postgresql")
        .putNull("engine").put("metadataVersion", 1);
    instance.set("connection", connection);
    instance.set("tables", JSON.createArrayNode().add(table));
    byte[] instanceBody = JSON.writeValueAsBytes(instance);

    metadataStub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    metadataPort = metadataStub.getAddress().getPort();
    metadataStub.createContext("/api/instances/pg_prod",
        exchange -> respond(exchange, 200, instanceBody));
    metadataStub.start();

    // 3) rewrite stub: one SELECT statement carrying masked/rowFiltered and the
    //    rewrittenSql read from the AtomicReference at request time.
    rewriteStub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    rewritePort = rewriteStub.getAddress().getPort();
    rewriteStub.createContext("/api/rewrite/instances/pg_prod", exchange -> {
      String sql;
      try {
        sql = JSON.readTree(exchange.getRequestBody()).path("sql").asText("");
      } catch (IOException e) {
        sql = "";
      }
      ObjectNode statement = JSON.createObjectNode()
          .put("ordinal", 1)
          .put("originalSql", sql)
          .put("rewrittenSql", stubsRewrittenSql.get())
          .put("masked", true)
          .put("rowFiltered", true)
          .put("kind", "SELECT");
      ObjectNode payload = JSON.createObjectNode();
      payload.set("statements", JSON.createArrayNode().add(statement));
      respond(exchange, 200, JSON.writeValueAsBytes(payload));
    });
    rewriteStub.start();
  }

  @AfterAll
  static void stopAll() throws Exception {
    if (rewriteStub != null) {
      rewriteStub.stop(0);
    }
    if (metadataStub != null) {
      metadataStub.stop(0);
    }
    if (pg != null) {
      pg.close();
    }
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  @DynamicPropertySource
  static void upstream(DynamicPropertyRegistry r) {
    r.add("upstream.metadata-base-url", () -> "http://127.0.0.1:" + metadataPort);
    r.add("upstream.rewrite-base-url", () -> "http://127.0.0.1:" + rewritePort);
    r.add("upstream.metadata-api-key", () -> "k");
    r.add("upstream.rewrite-api-key", () -> "k");
  }

  @TestConfiguration
  static class Stubs {

    /** passwordRef never touches the process environment: the embedded PG
     * password is served straight from this stub. */
    @Bean
    @Primary
    CredentialSource credentialSource() {
      return ref -> "postgres";
    }

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    Consumer<AuditEvent> capturingAuditSink() {
      return AUDIT::add;
    }
  }

  @Autowired MockMvc mockMvc;

  @Test
  void maskedRowFilteredResultFromRealDatabase() throws Exception {
    stubsRewrittenSql.set(ACTIVE_ROWS_PLAN);
    int auditBefore = Stubs.AUDIT.size();
    var mvcResult = mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg_prod\",\"sql\":\"SELECT phone FROM customer\","
                + "\"user\":\"alice\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(1))                 // archived 行被行过滤排除
        .andExpect(jsonPath("$.rows[0][0]").value("138****5678"))   // 真 UDF 计算结果
        .andExpect(jsonPath("$.masked").value(true))
        .andExpect(jsonPath("$.rowFiltered").value(true))
        .andExpect(jsonPath("$.truncated").value(false));

    List<AuditEvent> successes = auditEventsSince(auditBefore, AuditEvent.SUCCESS);
    assertThat(successes).hasSize(1);
    AuditEvent event = successes.get(0);
    assertThat(event.service()).isEqualTo("mask-query");
    assertThat(event.instance()).isEqualTo("pg_prod");
    assertThat(event.detail()).containsEntry("rowCount", 1);
  }

  @Test
  void statementTimeoutBecomesQueryTimeout() throws Exception {
    stubsRewrittenSql.set("SELECT pg_sleep(5)");   // 语句超时 2s < 5s 睡眠
    int auditBefore = Stubs.AUDIT.size();
    var mvcResult = mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg_prod\",\"sql\":\"SELECT pg_sleep(5)\"}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.code").value(QueryException.QUERY_TIMEOUT));

    List<AuditEvent> failures = auditEventsSince(auditBefore, AuditEvent.FAILURE);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0).errorCode()).isEqualTo(QueryException.QUERY_TIMEOUT);
  }

  @Test
  void truncationDetectedAgainstRealRows() throws Exception {
    stubsRewrittenSql.set("SELECT id FROM public.customer");  // 真库 2 行
    var mvcResult = mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg_prod\",\"sql\":\"SELECT id FROM customer\","
                + "\"maxRows\":1}"))
        .andExpect(request().asyncStarted())
        .andReturn();
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(1))
        .andExpect(jsonPath("$.truncated").value(true));
  }

  /** QUERY 事件增量：捕获列表是跨用例共享的静态字段，只看本用例新增的部分。 */
  private static List<AuditEvent> auditEventsSince(int before, String outcome) {
    return Stubs.AUDIT.stream()
        .skip(before)
        .filter(event -> AuditEvent.QUERY.equals(event.eventType()) && outcome.equals(event.outcome()))
        .toList();
  }
}
