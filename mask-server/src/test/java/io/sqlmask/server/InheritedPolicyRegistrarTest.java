package io.sqlmask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.rewrite.InheritedColumn;
import io.sqlmask.rewrite.InheritedTable;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import io.sqlmask.rewrite.StatementKind;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InheritedPolicyRegistrar} against a stub upstream (JDK built-in
 * {@link HttpServer}): the registrar must, before any write, pull the
 * <b>whole</b> policy list ({@code GET /api/instances/{name}/policies}) and
 * work from that whole-view — target-column conflict rejection and inherited
 * content (subjects+udf+arguments) both come from all subjects' policies, not
 * from the subject-sliced items the engine saw. Then, in order of concern, the
 * policy service table PUT, one policy POST per inherited column, and the
 * metadata structure PUT — mirroring the real wire contracts of
 * {@code PolicyAdminController} and {@code MetadataAdminController}. Admin
 * reads/writes carry the admin API key while the data-plane snapshot GET
 * carries the data key; every outbound request forwards
 * {@code X-Originating-User}.
 */
class InheritedPolicyRegistrarTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 源列 crm.public.customer.phone 的启用 DATAMASK 策略(全量视角)。 */
  private static final String SOURCE_PHONE_POLICY = """
      [{"name":"crm.phone","policyType":"datamask","isEnabled":true,"priority":0,
        "resource":{"catalog":"crm","schema":"public","table":"customer",
                    "columns":["phone"],"inheritColumns":[]},
        "subjects":{"users":["analyst"],"groups":["devs"]},
        "udf":"mask_full","arguments":[7,8],"filterExpr":null}]
      """;

  @Test
  void registersTableThenColumnsThenStructure() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "data-key", "admin-key").register("demo", "alice",
          List.of(ctasWithInheritedPhone()));
      List<String> paths = stub.calls.stream().map(c -> c.method() + " " + c.path()).toList();
      assertTrue(paths.contains("GET /api/instances/demo/policies"));
      assertTrue(paths.contains("GET /api/metadata/instances/demo"));
      assertTrue(paths.contains("PUT /api/instances/demo/tables"));
      assertTrue(paths.contains("POST /api/instances/demo/policies"));
      assertTrue(paths.contains("PUT /api/meta/instances/demo/structure"));
    }
  }

  @Test
  void mergesStructureWithExistingTablesInsteadOfReplacing() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    stub.snapshotJson = """
        {"instance":"demo","dialect":"postgresql","metadataVersion":3,
         "tables":[{"catalog":"crm","schema":"public","name":"existing",
                    "columns":[{"name":"id","type":"bigint"}]}]}
        """;
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      JsonNode tables = JSON.readTree(stub.calls.stream()
          .filter(c -> c.method().equals("PUT") && c.path().equals("/api/meta/instances/demo/structure"))
          .findFirst().orElseThrow().body());
      // 合并而非替换:既有表 crm.public.existing 保留(列原样),目标表 customer_copy 追加
      JsonNode existing = tableByName(tables, "existing");
      JsonNode copy = tableByName(tables, "customer_copy");
      assertEquals("bigint", columnType(existing, "id"));
      assertEquals("varchar", columnType(copy, "phone"));
    }
  }

  @Test
  void structureSnapshotFailureFailsWholeRewrite() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    stub.snapshotStatus = 404;
    stub.snapshotJson = "{\"error\":\"instance not found\"}";
    try (stub) {
      SqlMaskException failure = assertThrows(SqlMaskException.class,
          () -> registrar(stub, "k", null).register("demo", "alice",
              List.of(ctasWithInheritedPhone())));
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, failure.getCode());
      assertTrue(failure.getMessage().contains("GET /api/metadata/instances/demo"));
      assertTrue(failure.getMessage().contains("instance not found"));
    }
  }

  // ----- 修复 1:全量冲突检查(在写任何东西之前,整批失败) -----

  @Test
  void targetColumnWithExistingEnabledPolicyIsRejectedBeforeAnyWrite() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = """
        [{"name":"copy.phone","policyType":"datamask","isEnabled":true,"priority":0,
          "resource":{"catalog":"CRM","schema":"Public","table":"customer_copy",
                      "columns":["PHONE"],"inheritColumns":[]},
          "subjects":{"users":["*"]},"udf":"mask_phone","arguments":[]}]
        """;
    try (stub) {
      SqlMaskException failure = assertThrows(SqlMaskException.class,
          () -> registrar(stub, "k", null).register("demo", "alice",
              List.of(ctasWithInheritedPhone())));
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, failure.getCode());
      assertTrue(failure.getMessage().contains("已有自己的脱敏策略"), failure.getMessage());
      // 资源列大小写归一后命中,且拒绝发生在任何写入之前
      List<String> writes = stub.calls.stream()
          .map(c -> c.method() + " " + c.path())
          .filter(p -> p.startsWith("PUT") || p.startsWith("POST"))
          .toList();
      assertTrue(writes.isEmpty(), () -> "拒绝前不得有任何写入: " + writes);
    }
  }

  @Test
  void disabledTargetPolicyDoesNotConflict() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = """
        [{"name":"copy.phone","policyType":"datamask","isEnabled":false,"priority":0,
          "resource":{"catalog":"crm","schema":"public","table":"customer_copy",
                      "columns":["phone"],"inheritColumns":[]},
          "subjects":{"users":["*"]},"udf":"mask_phone","arguments":[]},
         """ + SOURCE_PHONE_POLICY.substring(1);
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      assertTrue(stub.calls.stream().anyMatch(c ->
          c.method().equals("POST") && c.path().equals("/api/instances/demo/policies")));
    }
  }

  // ----- 修复 3:全量继承内容(subjects+udf+arguments 原样取自全量策略) -----

  @Test
  void inheritedContentComesFromWholePolicyViewNotSubjectSlice() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      JsonNode posted = JSON.readTree(stub.calls.stream()
          .filter(c -> c.method().equals("POST") && c.path().equals("/api/instances/demo/policies"))
          .findFirst().orElseThrow().body());
      // 内容来自全量策略(完整 subjects + udf + arguments),而非引擎的 subject 切片 items
      assertEquals("auto.inherit.customer_copy.phone", posted.path("name").asText());
      assertEquals("mask_full", posted.path("udf").asText());
      assertEquals("analyst", posted.path("subjects").path("users").get(0).asText());
      assertEquals("devs", posted.path("subjects").path("groups").get(0).asText());
      assertEquals(7, posted.path("arguments").get(0).asInt());
      assertEquals("customer_copy", posted.path("resource").path("table").asText());
      assertEquals("phone", posted.path("resource").path("columns").get(0).asText());
      assertTrue(posted.path("resource").path("inheritColumns").isEmpty(),
          "目标资源不声明 inheritOnCopy,避免链式注册");
    }
  }

  @Test
  void multiplePoliciesOnSourceColumnProduceSuffixedStrategies() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = """
        [{"name":"crm.phone.auditor","policyType":"datamask","isEnabled":true,"priority":1,
          "resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},
          "subjects":{"users":["auditor"]},"udf":"mask_audit","arguments":[]},
         {"name":"crm.phone","policyType":"datamask","isEnabled":true,"priority":0,
          "resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},
          "subjects":{"users":["analyst"]},"udf":"mask_full","arguments":[]}]
        """;
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      List<String> names = stub.calls.stream()
          .filter(c -> c.method().equals("POST") && c.path().equals("/api/instances/demo/policies"))
          .map(c -> {
            try {
              return JSON.readTree(c.body()).path("name").asText();
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          })
          .toList();
      assertEquals(List.of("auto.inherit.customer_copy.phone",
          "auto.inherit.customer_copy.phone.2"), names);
    }
  }

  @Test
  void sourceColumnWithoutInheritablePolicyIsRejectedBeforeAnyWrite() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = "[]";
    try (stub) {
      SqlMaskException failure = assertThrows(SqlMaskException.class,
          () -> registrar(stub, "k", null).register("demo", "alice",
              List.of(ctasWithInheritedPhone())));
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, failure.getCode());
      assertTrue(failure.getMessage().contains("无可继承的完整策略"), failure.getMessage());
      assertTrue(failure.getMessage().contains("crm.public.customer.phone"), failure.getMessage());
      List<String> writes = stub.calls.stream()
          .map(c -> c.method() + " " + c.path())
          .filter(p -> p.startsWith("PUT") || p.startsWith("POST"))
          .toList();
      assertTrue(writes.isEmpty(), () -> "拒绝前不得有任何写入: " + writes);
    }
  }

  @Test
  void disabledSourcePolicyIsNotInheritable() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = """
        [{"name":"crm.phone","policyType":"datamask","isEnabled":false,"priority":0,
          "resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},
          "subjects":{"users":["analyst"]},"udf":"mask_full","arguments":[]}]
        """;
    try (stub) {
      SqlMaskException failure = assertThrows(SqlMaskException.class,
          () -> registrar(stub, "k", null).register("demo", "alice",
              List.of(ctasWithInheritedPhone())));
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, failure.getCode());
      assertTrue(failure.getMessage().contains("无可继承的完整策略"), failure.getMessage());
    }
  }

  // ----- 修复 5:注册完整目标表结构(混合复制后结构残缺会导致后续查询 fail-closed) -----

  @Test
  void tablesAndStructurePreferFullInheritedTableColumns() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice",
          List.of(ctasWithInheritedPhoneAndStructure()));
      JsonNode policyTables = JSON.readTree(stub.calls.stream()
          .filter(c -> c.method().equals("PUT") && c.path().equals("/api/instances/demo/tables"))
          .findFirst().orElseThrow().body()).path("tables");
      // 完整结构:非继承输出列 name 也进入目标表结构(混合复制场景)
      assertEquals("varchar", columnType(tableByName(policyTables, "customer_copy"), "name"));
      JsonNode structure = JSON.readTree(stub.calls.stream()
          .filter(c -> c.method().equals("PUT") && c.path().equals("/api/meta/instances/demo/structure"))
          .findFirst().orElseThrow().body());
      JsonNode copy = tableByName(structure, "customer_copy");
      assertEquals("varchar", columnType(copy, "phone"));
      assertEquals("varchar", columnType(copy, "name"));
    }
  }

  @Test
  void withoutFullStructureFallsBackToInheritedColumnsOnly() throws Exception {
    // 旧结果(无 inheritedTables)仍按继承列拼装——向后兼容
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      JsonNode structure = JSON.readTree(stub.calls.stream()
          .filter(c -> c.method().equals("PUT") && c.path().equals("/api/meta/instances/demo/structure"))
          .findFirst().orElseThrow().body());
      JsonNode copy = tableByName(structure, "customer_copy");
      assertEquals("varchar", columnType(copy, "phone"));
      assertEquals(1, copy.path("columns").size(), () -> structure.toString());
    }
  }

  // ----- 修复 2:admin/data 双 key 配置 -----

  @Test
  void adminWritesUseAdminKeyWhileDataSnapshotUsesDataKey() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "data-key", "admin-key").register("demo", "alice",
          List.of(ctasWithInheritedPhone()));
      assertEquals("data-key", apiKeyOf(stub, "GET /api/metadata/instances/demo"),
          "数据面快照 GET 必须带 data key");
      assertEquals("admin-key", apiKeyOf(stub, "GET /api/instances/demo"));
      assertEquals("admin-key", apiKeyOf(stub, "GET /api/instances/demo/policies"));
      assertEquals("admin-key", apiKeyOf(stub, "PUT /api/instances/demo/tables"));
      assertEquals("admin-key", apiKeyOf(stub, "POST /api/instances/demo/policies"));
      assertEquals("admin-key", apiKeyOf(stub, "PUT /api/meta/instances/demo/structure"),
          "admin 写(/structure)必须带 admin key");
    }
  }

  @Test
  void missingAdminApiKeyFallsBackToDataKey() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      // 单 key 部署:2 参构造器(adminApiKey 缺省为 null)→ 所有请求回落 data key
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      for (String call : List.of("GET /api/instances/demo", "GET /api/instances/demo/policies",
          "PUT /api/instances/demo/tables", "POST /api/instances/demo/policies",
          "GET /api/metadata/instances/demo", "PUT /api/meta/instances/demo/structure")) {
        assertEquals("k", apiKeyOf(stub, call), call);
      }
    }
  }

  // ----- 修复 6:审计触发者透传 -----

  @Test
  void forwardsOriginatingUserHeaderToUpstreams() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "k", null).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      assertFalse(stub.calls.isEmpty());
      for (Call call : stub.calls) {
        assertEquals("alice", call.originUser(),
            () -> call.method() + " " + call.path() + " 应携带 X-Originating-User");
      }
    }
  }

  @Test
  void blankOriginatingUserSendsNoHeader() throws Exception {
    StubUpstream stub = new StubUpstream();
    stub.policiesJson = SOURCE_PHONE_POLICY;
    try (stub) {
      registrar(stub, "k", null).register("demo", " ", List.of(ctasWithInheritedPhone()));
      for (Call call : stub.calls) {
        assertNull(call.originUser(), () -> call.method() + " " + call.path());
      }
    }
  }

  // ----- stub 上游 -----

  /** 一次出站请求的记录:方法、路径与关键请求头/体。 */
  private record Call(String method, String path, String apiKey, String originUser,
      String body) {
  }

  /** policy 与 metadata 指向同一 stub:GET policies / GET instance / 快照可注入。 */
  private static final class StubUpstream implements AutoCloseable {

    final HttpServer server;
    final List<Call> calls = new ArrayList<>();
    String policiesJson = "[]";
    String instanceJson = "{\"name\":\"demo\",\"dialect\":\"postgresql\",\"tables\":[]}";
    String snapshotJson = "{\"instance\":\"demo\",\"dialect\":\"postgresql\","
        + "\"metadataVersion\":1,\"tables\":[]}";
    int snapshotStatus = 200;

    StubUpstream() {
      try {
        server = HttpServer.create(new InetSocketAddress(0), 0);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
      server.createContext("/", ex -> {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(),
            ex.getRequestHeaders().getFirst("X-Api-Key"),
            ex.getRequestHeaders().getFirst("X-Originating-User"), body));
        String call = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        boolean snapshotMiss = call.equals("GET /api/metadata/instances/demo")
            && snapshotStatus != 200;
        String payload = snapshotMiss
            ? snapshotJson
            : switch (call) {
              case "GET /api/instances/demo" -> instanceJson;
              case "GET /api/instances/demo/policies" -> policiesJson;
              case "GET /api/metadata/instances/demo" -> snapshotJson;
              default -> "{\"ok\":true}";
            };
        byte[] response = payload.getBytes();
        ex.sendResponseHeaders(snapshotMiss ? snapshotStatus : 200, response.length);
        ex.getResponseBody().write(response);
        ex.close();
      });
      server.start();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** policy 与 metadata 指向同一 stub 的注册器;adminApiKey 为 null 时回落 data key。 */
  private static InheritedPolicyRegistrar registrar(StubUpstream stub, String dataKey,
      String adminKey) {
    String base = "http://localhost:" + stub.server.getAddress().getPort();
    return new InheritedPolicyRegistrar(new InstanceRewriteConfig.Upstreams(
        new InstanceRewriteConfig.Upstreams.Service(base, dataKey, adminKey),
        new InstanceRewriteConfig.Upstreams.Service(base, dataKey, adminKey)));
  }

  private static String apiKeyOf(StubUpstream stub, String methodAndPath) {
    return stub.calls.stream()
        .filter(c -> (c.method() + " " + c.path()).equals(methodAndPath))
        .findFirst().orElseThrow(() -> new AssertionError("缺少调用 " + methodAndPath))
        .apiKey();
  }

  /** CTAS 目标表 crm.public.customer_copy,继承列 phone(类型 varchar,引擎填写)。 */
  private static StatementRewrite ctasWithInheritedPhone() {
    return new StatementRewrite(1, "sql", "sql", false, false, StatementKind.CTAS,
        List.of(inheritedPhone()));
  }

  /** 同上,另带引擎输出的完整目标表结构(phone + name,混合复制场景)。 */
  private static StatementRewrite ctasWithInheritedPhoneAndStructure() {
    return new StatementRewrite(1, "sql", "sql", false, false, StatementKind.CTAS,
        List.of(inheritedPhone()),
        List.of(new InheritedTable("crm", "public", "customer_copy",
            List.of(new InheritedTable.ColumnInfo("phone", "varchar"),
                new InheritedTable.ColumnInfo("name", "varchar")))));
  }

  /** 继承条目:目标列 crm.public.customer_copy.phone ← 源列 crm.public.customer.phone;
   * items 是引擎的 subject 切片(注册不再消费,仅保留引擎侧产出形状)。 */
  private static InheritedColumn inheritedPhone() {
    return new InheritedColumn("crm", "public", "customer_copy", "phone", "varchar",
        ColumnOrigin.of(ColumnKey.of("crm", "public", "customer", "phone"),
            "crm.public.customer", false),
        List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")),
            "mask_phone", List.of())));
  }

  private static JsonNode tableByName(JsonNode tables, String name) {
    for (JsonNode table : tables) {
      if (name.equals(table.path("name").asText())) {
        return table;
      }
    }
    throw new AssertionError("structure PUT 缺少表 '" + name + "': " + tables);
  }

  private static String columnType(JsonNode table, String column) {
    for (JsonNode entry : table.path("columns")) {
      if (column.equals(entry.path("name").asText())) {
        return entry.path("type").asText();
      }
    }
    throw new AssertionError(
        "表 '" + table.path("name").asText() + "' 缺少列 '" + column + "': " + table);
  }
}
