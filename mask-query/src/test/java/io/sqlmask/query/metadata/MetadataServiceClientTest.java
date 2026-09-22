package io.sqlmask.query.metadata;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataServiceClientTest {

  static HttpServer stub;
  static MetadataServiceClient client;
  static volatile int status = 200;
  static volatile String body = "{}";
  static final AtomicReference<String> requestedPath = new AtomicReference<>();

  @BeforeAll
  static void start() throws Exception {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/api/instances/pg_prod", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    // 空格实例名的编码路径：记录命中的原始路径，验证 URL 编码后仍能路由。
    // （JDK HttpServer 按解码后路径匹配 context，故注册串带字面空格；
    // 客户端必须编码为 %20——表单编码的 '+' 会被服务端按字面 '+' 解码）
    stub.createContext("/api/instances/pg prod", exchange -> {
      requestedPath.set(exchange.getRequestURI().getRawPath());
      byte[] bytes = ("{\"name\":\"pg prod\",\"dialect\":\"postgresql\","
          + "\"metadataVersion\":1}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    stub.start();
    client = new MetadataServiceClient(
        "http://127.0.0.1:" + stub.getAddress().getPort(), "k");
  }

  @AfterAll
  static void stop() { stub.stop(0); }

  @Test
  void parsesDetailAndDerivesEngine() {
    status = 200;
    body = """
        {"name":"pg_prod","dialect":"postgresql","engine":null,"metadataVersion":3,
         "connection":{"host":"h","port":5432,"database":"crm","dbUser":"u",
           "passwordRef":"REF","sslmode":"disable","connectTimeoutSeconds":5}}
        """;
    InstanceView view = client.fetch("pg_prod");
    assertThat(view.engine()).isEqualTo("postgresql");
    assertThat(view.connection().port()).isEqualTo(5432);
  }

  @Test
  void encodesInstanceNameIntoPathSegment() {
    // 修复前：URI.create(".../pg prod") 抛 IllegalArgumentException；修复后：
    // 空格按路径段规则编码为 %20（表单编码的 '+' 会被服务端按字面 '+' 解码）
    InstanceView view = client.fetch("pg prod");
    assertThat(view.name()).isEqualTo("pg prod");
    assertThat(view.dialect()).isEqualTo("postgresql");
    assertThat(requestedPath.get()).isEqualTo("/api/instances/pg%20prod");
  }

  @Test
  void derivesHiveEngineFromDialectWhenEngineMissing() {
    // 批2终审修复（C1）：客户端推导规则与 metadata 侧同源——hive/sparksql 不得再
    // 兜底成 mysql（否则查询侧按 mysql 引擎连 HiveServer2）
    status = 200;
    body = "{\"name\":\"pg_prod\",\"dialect\":\"hive\",\"engine\":null,\"metadataVersion\":3}";
    assertThat(client.fetch("pg_prod").engine()).isEqualTo("hive");
    body = "{\"name\":\"pg_prod\",\"dialect\":\"sparksql\",\"engine\":null,\"metadataVersion\":3}";
    assertThat(client.fetch("pg_prod").engine()).isEqualTo("sparksql");
  }

  @Test
  void maps404And401() {
    status = 404; body = "{}";
    assertThatThrownBy(() -> client.fetch("pg_prod"))
        .hasFieldOrPropertyWithValue("code", "INSTANCE_NOT_FOUND");
    status = 401; body = "{}";
    assertThatThrownBy(() -> client.fetch("pg_prod"))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR")
        .hasMessageContaining("API key");
  }
}
