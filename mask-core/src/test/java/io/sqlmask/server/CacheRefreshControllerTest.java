package io.sqlmask.server;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.config.source.PolicyServiceConfigSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual cache drop and the scheduled refresh entry (no live service needed). */
class CacheRefreshControllerTest {

  private static final String EFFECTIVE_BODY = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[]},
         "columns":[],
         "policies":{}}}
      """;

  private static HttpServer startStub(AtomicReference<Integer> status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/effective/pg_prod", exchange -> {
      byte[] bytes = EFFECTIVE_BODY.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
    return server;
  }

  @Test
  void cachesPerInstanceAndClearsSelectivelyOrWholly() {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    PolicyServiceConfigSource a = sources.get("a");
    assertThat(sources.get("a")).isSameAs(a);
    assertThat(sources.get("b")).isNotSameAs(a);

    assertThat(sources.clear("a")).isEqualTo(1);
    assertThat(sources.get("a")).isNotSameAs(a);
    assertThat(sources.clear(null)).isEqualTo(2); // a (rebuilt above) + b
  }

  @Test
  void scheduledRefreshSurvivesUnreachableService() {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    sources.get("a");
    assertThatCode(sources::refreshAll).doesNotThrowAnyException(); // stale semantics
  }

  // ---- 有缓存的实例轮询失败：异常被吞、stale 缓存保留（§4.1 第 1 条 P0）----

  @Test
  void refreshAllSwallowsHttp500AndServesStaleCache() throws IOException {
    AtomicReference<Integer> status = new AtomicReference<>(200);
    HttpServer server = startStub(status);
    try {
      InstanceConfigSources sources = new InstanceConfigSources(
          "http://127.0.0.1:" + server.getAddress().getPort(), "k");
      assertThat(sources.get("pg_prod").load().configVersion()).isEqualTo(1); // 第一次成功

      status.set(500); // 后续轮询失败
      assertThatCode(sources::refreshAll).doesNotThrowAnyException(); // 异常被吞
      assertThat(sources.get("pg_prod").load().configVersion()).isEqualTo(1); // stale 保留
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refreshAllSwallowsConnectionRefusedAndServesStaleCache() throws IOException {
    AtomicReference<Integer> status = new AtomicReference<>(200);
    HttpServer server = startStub(status);
    InstanceConfigSources sources;
    try {
      sources = new InstanceConfigSources(
          "http://127.0.0.1:" + server.getAddress().getPort(), "k");
      assertThat(sources.get("pg_prod").load().configVersion()).isEqualTo(1); // 第一次成功
    } finally {
      server.stop(0); // 稍后拒连
    }
    assertThatCode(sources::refreshAll).doesNotThrowAnyException(); // 拒连异常被吞
    assertThat(sources.get("pg_prod").load().configVersion()).isEqualTo(1); // stale 保留
  }

  @Test
  void refreshEndpointReportsClearedCountAndAcceptsEmptyBody() throws Exception {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    sources.get("a");
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new CacheRefreshController(sources)).build();

    mvc.perform(post("/admin/cache/refresh")
            .contentType("application/json")
            .content("{\"instance\": \"a\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(1));
    mvc.perform(post("/admin/cache/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(0));
  }
}
