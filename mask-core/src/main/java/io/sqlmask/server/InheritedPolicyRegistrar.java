package io.sqlmask.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.rewrite.InheritedColumn;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 改写后自动注册"复制表语句"的继承策略:改写引擎返回携带 {@code inheritedColumns}
 * 的语句后,本组件在返回前把目标表结构补入策略服务(合并 {@code PUT /tables})、为
 * 每个继承列创建 dataMask 策略(名 {@code auto.inherit.<table>.<column>},资源为
 * 目标表列,items 原样复制,目标资源上不声明 inheritOnCopy 以避免链式注册),并把
 * 目标表结构登记进元数据服务({@code PUT /structure})。任一上游调用非 2xx 即抛
 * {@link SqlMaskException},使整个改写请求失败——绝不留下"数据已干净写入但继承策略
 * 未注册"的静默状态。
 *
 * <p>wire 契约对 {@code PolicyAdminController}(策略服务)与
 * {@code MetadataAdminController}(元数据服务)只读:本地定义轻量 DTO,不依赖那两个
 * 服务模块。</p>
 */
@Component
public class InheritedPolicyRegistrar {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final InstanceRewriteConfig.Upstreams.Service policyService;
  private final InstanceRewriteConfig.Upstreams.Service metadataService;
  private final HttpClient http;

  @Autowired
  public InheritedPolicyRegistrar(InstanceRewriteConfig.Upstreams upstreams) {
    this(upstreams == null ? null : upstreams.policyService(),
        upstreams == null ? null : upstreams.metadataService());
  }

  public InheritedPolicyRegistrar(InstanceRewriteConfig.Upstreams.Service policyService,
      InstanceRewriteConfig.Upstreams.Service metadataService) {
    this.policyService = policyService;
    this.metadataService = metadataService;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** 收集所有继承列,按目标表分组;无继承列时直接返回(读取语句无副作用)。 */
  public void register(String instanceName, String originUser, List<StatementRewrite> statements) {
    List<InheritedColumn> inherited = new ArrayList<>();
    if (statements != null) {
      for (StatementRewrite statement : statements) {
        if (statement.inheritedColumns() != null) {
          inherited.addAll(statement.inheritedColumns());
        }
      }
    }
    if (inherited.isEmpty()) {
      return;
    }
    Map<String, TableGroup> byTable = groupByTable(inherited);
    requireConfigured(instanceName, originUser);
    List<TableDto> merged = mergeTables(instanceName, byTable);
    putTables(instanceName, merged);
    for (TableGroup group : byTable.values()) {
      for (InheritedColumn column : group.columns()) {
        registerColumnPolicies(instanceName, column);
      }
    }
    putStructure(instanceName, byTable);
  }

  private void requireConfigured(String instanceName, String originUser) {
    String detail = "rewrite 继承策略注册失败:实例 '" + instanceName + "'"
        + (originUser == null ? "" : "(user '" + originUser + "')") + " 需要策略服务与元数据服务"
        + "上游(sqlmask.policy-service.base-url / sqlmask.metadata-service.base-url)";
    if (policyService == null || blank(policyService.baseUrl())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, detail);
    }
    if (metadataService == null || blank(metadataService.baseUrl())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, detail);
    }
  }

  /** 目标表三元组按 catalog.schema.table 分组,保持语句出现顺序。 */
  private static Map<String, TableGroup> groupByTable(List<InheritedColumn> inherited) {
    Map<String, TableGroup> byTable = new LinkedHashMap<>();
    for (InheritedColumn column : inherited) {
      String key = key(column.targetCatalog(), column.targetSchema(), column.targetTable());
      TableGroup group = byTable.get(key);
      if (group == null) {
        group = new TableGroup(column.targetCatalog(), column.targetSchema(),
            column.targetTable(), new ArrayList<>());
        byTable.put(key, group);
      }
      group.columns().add(column);
    }
    return byTable;
  }

  /** GET 现有 tables 并与目标表合并:已存在的表按列合并,新表追加。 */
  private List<TableDto> mergeTables(String instanceName, Map<String, TableGroup> byTable) {
    HttpResponse<String> response = get(policyBase(instanceName), policyService.apiKey());
    requireOk(response, "GET /api/instances/" + instanceName);
    InstanceDto instance = readBody(response.body(), InstanceDto.class,
        "policy 服务返回了无法解析的实例响应");
    Map<String, TableDto> merged = new LinkedHashMap<>();
    if (instance.tables() != null) {
      for (TableDto table : instance.tables()) {
        merged.put(key(table.catalog(), table.schema(), table.name()), table);
      }
    }
    for (TableGroup group : byTable.values()) {
      String key = key(group.catalog(), group.schema(), group.table());
      TableDto existing = merged.get(key);
      merged.put(key, existing == null
          ? new TableDto(group.catalog(), group.schema(), group.table(), columnsOf(group))
          : mergeColumns(existing, group));
    }
    return List.copyOf(merged.values());
  }

  /** 目标表全部列(按列名去重,类型用引擎填写的来源列 typeDeclaration,可为 null)。 */
  private static List<ColumnDto> columnsOf(TableGroup group) {
    Map<String, ColumnDto> columns = new LinkedHashMap<>();
    for (InheritedColumn column : group.columns()) {
      columns.putIfAbsent(normalize(column.targetColumn()),
          new ColumnDto(column.targetColumn(), column.targetColumnType()));
    }
    return List.copyOf(columns.values());
  }

  /** 表已存在(INSERT INTO 既有表):保留既有列,补入缺失的继承列。 */
  private static TableDto mergeColumns(TableDto existing, TableGroup group) {
    Map<String, ColumnDto> columns = new LinkedHashMap<>();
    if (existing.columns() != null) {
      for (ColumnDto column : existing.columns()) {
        columns.put(normalize(column.name()), column);
      }
    }
    for (ColumnDto column : columnsOf(group)) {
      columns.putIfAbsent(normalize(column.name()), column);
    }
    return new TableDto(existing.catalog(), existing.schema(), existing.name(),
        List.copyOf(columns.values()));
  }

  /** 每个继承列一条/多条 dataMask 策略:名称 auto.inherit.<table>.<column>,
   * 多条 items 依次以 .2/.3 后缀区分,优先级按 items 顺序递减(优先级高者在前)。 */
  private void registerColumnPolicies(String instanceName, InheritedColumn column) {
    List<DataMaskItem> items = column.items();
    for (int i = 0; i < items.size(); i++) {
      DataMaskItem item = items.get(i);
      String name = "auto.inherit." + column.targetTable() + "." + column.targetColumn()
          + (i == 0 ? "" : "." + (i + 1));
      ResourceDto resource = new ResourceDto(column.targetCatalog(), column.targetSchema(),
          column.targetTable(), List.of(column.targetColumn()), List.of());
      SubjectSelector selector = item.selector();
      SubjectDto subjects = new SubjectDto(selector.users(), selector.groups());
      // 目标资源不声明 inheritOnCopy:避免已注册的继承策略再次触发链式注册
      PolicyDto policy = new PolicyDto(name, "dataMask", true, items.size() - 1 - i, resource,
          subjects, item.udf(), item.arguments(), null);
      HttpResponse<String> response = post(policyBase(instanceName) + "/policies", policy,
          policyService.apiKey());
      requireOk(response, "POST /api/instances/" + instanceName + "/policies (" + name + ")");
    }
  }

  /** 合并后的 tables 整体 PUT 到策略服务(整体替换语义)。 */
  private void putTables(String instanceName, List<TableDto> tables) {
    HttpResponse<String> response = put(policyBase(instanceName) + "/tables",
        new TablesDto(tables), policyService.apiKey());
    requireOk(response, "PUT /api/instances/" + instanceName + "/tables");
  }

  /** 目标表结构登记进元数据服务:一次 PUT 全部目标表(端点整体替换,保持目标表齐全)。 */
  private void putStructure(String instanceName, Map<String, TableGroup> byTable) {
    List<TablePayload> payloads = new ArrayList<>();
    for (TableGroup group : byTable.values()) {
      payloads.add(new TablePayload(group.catalog(), group.schema(), group.table(),
          columnsOf(group).stream()
              .map(column -> new ColumnPayload(column.name(), column.type()))
              .toList()));
    }
    HttpResponse<String> response = put(metadataBase(instanceName) + "/structure", payloads,
        metadataService.apiKey());
    requireOk(response, "PUT /api/instances/" + instanceName + "/structure");
  }

  // ----- wire helpers -----

  private String policyBase(String instanceName) {
    return trimSlash(policyService.baseUrl()) + "/api/instances/" + encode(instanceName);
  }

  private String metadataBase(String instanceName) {
    return trimSlash(metadataService.baseUrl()) + "/api/instances/" + encode(instanceName);
  }

  private HttpResponse<String> get(String url, String apiKey) {
    return send(withApiKey(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json"), apiKey)
        .GET()
        .build());
  }

  private HttpResponse<String> put(String url, Object body, String apiKey) {
    return send(withApiKey(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json"), apiKey)
        .PUT(HttpRequest.BodyPublishers.ofString(toJson(body)))
        .build());
  }

  private HttpResponse<String> post(String url, Object body, String apiKey) {
    return send(withApiKey(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json"), apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
        .build());
  }

  /** 所有请求带 X-Api-Key;apiKey 未配置时不加头(镜像 MetadataClient 的守卫)。 */
  private static HttpRequest.Builder withApiKey(HttpRequest.Builder builder, String apiKey) {
    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("X-Api-Key", apiKey);
    }
    return builder;
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "rewrite 继承策略注册失败:上游不可达于 '" + request.uri() + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "rewrite 继承策略注册失败:调用上游被中断于 '" + request.uri() + "'", e);
    }
  }

  private static String toJson(Object body) {
    try {
      return JSON.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "rewrite 继承策略注册失败:无法序列化请求体", e);
    }
  }

  private static <T> T readBody(String body, Class<T> type, String what) {
    try {
      return JSON.readValue(body, type);
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, what, e);
    }
  }

  /** 非 2xx(4xx/5xx)即抛错使改写整体失败;消息含响应体。 */
  private static void requireOk(HttpResponse<String> response, String what) {
    int code = response.statusCode();
    if (code >= 300) {
      String body = response.body();
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "rewrite 继承策略注册失败:" + what + " 返回 HTTP " + code
              + (body == null || body.isBlank() ? "" : " —— " + body.trim()));
    }
  }

  private static String encode(String instanceName) {
    return URLEncoder.encode(instanceName, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String trimSlash(String baseUrl) {
    return baseUrl == null ? "" : (baseUrl.endsWith("/")
        ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String normalize(String identifier) {
    return identifier == null ? "" : identifier.toLowerCase(Locale.ROOT);
  }

  private static String key(String catalog, String schema, String table) {
    return normalize(catalog) + "." + normalize(schema) + "." + normalize(table);
  }

  /** 一个目标表分组:目标三元组 + 该表的全部继承列。 */
  private record TableGroup(String catalog, String schema, String table,
      List<InheritedColumn> columns) {
  }

  // ----- wire DTOs(镜像 PolicyAdminController / MetadataAdminController 契约)-----

  record InstanceDto(String name, String dialect, List<TableDto> tables) {
  }

  record TableDto(String catalog, String schema, String name, List<ColumnDto> columns) {
  }

  record ColumnDto(String name, String type) {
  }

  record TablesDto(List<TableDto> tables) {
  }

  record ResourceDto(String catalog, String schema, String table, List<String> columns,
      List<String> inheritColumns) {
  }

  record SubjectDto(Set<String> users, Set<String> groups) {
  }

  record PolicyDto(String name, String policyType, boolean isEnabled, Integer priority,
      ResourceDto resource, SubjectDto subjects, String udf, List<Object> arguments,
      String filterExpr) {
  }

  record TablePayload(String catalog, String schema, String name, List<ColumnPayload> columns) {
  }

  record ColumnPayload(String name, String type) {
  }
}
