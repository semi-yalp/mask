package io.sqlmask.config.source;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyServiceConfigSourceTest {

  private static final String BODY_V1 = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"phone_mask"}],
         "policies":{"phone_mask":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private HttpServer server;
  private final AtomicReference<String> body = new AtomicReference<>(BODY_V1);
  private final AtomicReference<Integer> status = new AtomicReference<>(200);
  private final AtomicReference<String> seenKey = new AtomicReference<>("");
  private final AtomicReference<String> seenQuery = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/effective/pg_prod", exchange -> {
      seenKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      seenQuery.set(exchange.getRequestURI().getQuery());
      byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private PolicyServiceConfigSource source() {
    return new PolicyServiceConfigSource(
        "http://127.0.0.1:" + server.getAddress().getPort(), "secret", "pg_prod");
  }

  @Test
  void fetchesAndAssembles() {
    ConfigSource.ResolvedConfig resolved = source().load();
    assertEquals("postgresql", resolved.dialect());
    assertEquals(1, resolved.configVersion());
    assertEquals(1, resolved.config().tables().size());
    assertEquals("secret", seenKey.get());
  }

  @Test
  void refreshUpdatesCacheOnlyOnVersionChange() {
    PolicyServiceConfigSource s = source();
    s.load();
    assertTrue(s.refresh() == false); // 同版本
    body.set(BODY_V1.replace("\"configVersion\":1", "\"configVersion\":2"));
    assertTrue(s.refresh());
    assertEquals(2, s.load().configVersion());
  }

  @Test
  void staleCacheServedWhenServiceDown() {
    PolicyServiceConfigSource s = source();
    s.load();
    server.stop(0);
    assertEquals(1, s.load().configVersion()); // 仍返回缓存
  }

  @Test
  void noCacheAndServiceDownFailsClosed() {
    PolicyServiceConfigSource s = new PolicyServiceConfigSource(
        "http://127.0.0.1:1", "secret", "pg_prod"); // 端口 1 必然拒绝连接
    SqlMaskException e = assertThrows(SqlMaskException.class, s::load);
    assertEquals(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE, e.getCode());
  }

  @Test
  void notFoundMapsToInstanceNotFound() {
    status.set(404);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> source().load());
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void unauthorizedMapsToConfigError() {
    status.set(401);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> source().load());
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  // ---- 主体感知 ----

  @Test
  void loadBySubjectSendsQueryParamsAndCachesIndependently() {
    PolicyServiceConfigSource s = source();
    ConfigSource.ResolvedConfig alice = s.load(Subject.of("alice", List.of("a", "b")));
    assertEquals("postgresql", alice.dialect());
    assertTrue(seenQuery.get().contains("user=alice"));
    assertTrue(seenQuery.get().contains("groups=a"));
    assertTrue(seenQuery.get().contains("groups=b"));
    s.load(Subject.of("bob", List.of()));
    assertTrue(seenQuery.get().contains("user=bob"));
    s.load(Subject.of("alice", List.of("a", "b"))); // 命中缓存，不再发请求
    assertEquals("user=bob", seenQuery.get());
  }

  @Test
  void anonymousLoadSendsNoQuery() {
    source().load(Subject.anonymous());
    assertTrue(seenQuery.get() == null || seenQuery.get().isEmpty());
  }

  @Test
  void refreshUpdatesAllCachedSubjects() {
    PolicyServiceConfigSource s = source();
    s.load(Subject.of("alice", List.of()));
    s.load(Subject.of("bob", List.of()));
    assertFalse(s.refresh()); // 两主体同版本
    body.set(BODY_V1.replace("\"configVersion\":1", "\"configVersion\":2"));
    assertTrue(s.refresh());
    assertEquals(2, s.load(Subject.of("alice", List.of())).configVersion());
    assertEquals(2, s.load(Subject.of("bob", List.of())).configVersion());
  }

  @Test
  void coldSubjectFailsClosedWhenServiceDown() {
    PolicyServiceConfigSource s = source();
    s.load(Subject.of("alice", List.of())); // 温缓存主体
    server.stop(0);
    assertEquals(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
        assertThrows(SqlMaskException.class,
            () -> s.load(Subject.of("newbie", List.of()))).getCode());
    // 已缓存主体 stale 可用
    assertEquals("postgresql", s.load(Subject.of("alice", List.of())).dialect());
  }
}
