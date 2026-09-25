package io.sqlmask.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.InheritedColumn;
import io.sqlmask.rewrite.InheritedTable;
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
 * 目标表列,目标资源上不声明 inheritOnCopy 以避免链式注册),并把目标表结构登记进
 * 元数据服务——先经数据面只读快照({@code GET /api/metadata/instances/{name}})取
 * 既有结构,按表合并后再 {@code PUT /structure}({@code PUT /api/instances/{name}/structure}
 * 是整体替换语义,不合并会清掉实例其它表已登记的结构)。任一上游调用非 2xx 即抛
 * {@link SqlMaskException},使整个改写请求失败——绝不留下"数据已干净写入但继承策略
 * 未注册"的静默状态。
 *
 * <p><b>全量策略视角</b>:注册流程以策略服务的全量策略列表
 * ({@code GET /api/instances/{name}/policies})为准——引擎侧的有效配置按请求
 * subject 编译,只能看见该 subject 可见的策略切片;若据此建目标表策略,其他
 * subject(如 auditor)读目标表会静默不脱敏。因此注册前先做<b>全量冲突检查</b>
 * (目标表列在任一启用 DATAMASK 策略中出现即拒绝,在写任何东西之前),并从全量
 * 策略中收集源列的<b>完整 subjects+udf+arguments</b> 作为继承内容;某源列在全量
 * 策略中无任何启用 DATAMASK 策略即拒绝(fail-closed)。引擎产出的
 * {@code InheritedColumn.items()} 是 subject 切片,注册不再消费。</p>
 *
 * <p><b>双面密钥</b>:admin 面({@code /api/instances/**} 的读与写)用对应服务的
 * admin API key(未单独配置时回落 data key,单 key 部署零配置兼容);metadata 的
 * 数据面快照 GET 用 data key。</p>
 *
 * <p>wire 契约对 {@code PolicyAdminController}(策略服务)与
 * {@code MetadataAdminController}(元数据服务)只读:本地定义轻量 DTO,不依赖那两个
 * 服务模块。所有出站请求在 originUser 非空时带 {@code X-Originating-User},使上游
 * 服务的审计事件能记录触发者。</p>
 */
@Component
public class InheritedPolicyRegistrar {

  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
    Map<String, InheritedTable> structures = inheritedTablesByTarget(statements);
    requireConfigured(instanceName, originUser);
    // 次序:GET 全量策略 → 全量冲突检查 → 合并 tables → 逐条 POST policies → structure
    // —— 冲突检查在写任何东西之前完成,整批失败
    List<PolicyDto> policies = fetchPolicies(instanceName, originUser);
    requireNoTargetConflicts(policies, byTable);
    Map<String, List<InheritedItem>> inheritable = collectInheritableItems(policies, inherited);
    List<TableDto> merged = mergeTables(instanceName, byTable, structures, originUser);
    putTables(instanceName, merged, originUser);
    for (TableGroup group : byTable.values()) {
      for (InheritedColumn column : group.columns()) {
        registerColumnPolicies(instanceName, column,
            inheritable.get(itemKey(column)), originUser);
      }
    }
    putStructure(instanceName, byTable, structures, originUser);
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

  /** 引擎输出的目标表完整结构按三元组去重收集(多语句写同一目标表时取首个)。 */
  private static Map<String, InheritedTable> inheritedTablesByTarget(
      List<StatementRewrite> statements) {
    Map<String, InheritedTable> byTarget = new LinkedHashMap<>();
    if (statements == null) {
      return byTarget;
    }
    for (StatementRewrite statement : statements) {
      if (statement.inheritedTables() == null) {
        continue;
      }
      for (InheritedTable table : statement.inheritedTables()) {
        byTarget.putIfAbsent(key(table.catalog(), table.schema(), table.table()), table);
      }
    }
    return byTarget;
  }

  /** GET 实例的全量策略列表:admin 面 {@code GET /api/instances/{name}/policies}
   * 返回所有 subject 可见的完整策略——注册的冲突检查与继承内容都以此为唯一依据。 */
  private List<PolicyDto> fetchPolicies(String instanceName, String originUser) {
    HttpResponse<String> response = get(policyBase(instanceName) + "/policies",
        policyService.effectiveAdminApiKey(), originUser);
    requireOk(response, "GET /api/instances/" + instanceName + "/policies");
    List<PolicyDto> policies = readPolicies(response.body(),
        "rewrite 继承策略注册失败:policy 服务返回了无法解析的策略列表响应");
    return policies == null ? List.of() : policies;
  }

  /** 该策略是否为启用的 DATAMASK 列策略(wire 里 policyType 为小写 {@code datamask})。 */
  private static boolean enabledDataMask(PolicyDto policy) {
    return policy.isEnabled() && policy.policyType() != null
        && "datamask".equalsIgnoreCase(policy.policyType());
  }

  /** 全量冲突检查:目标表列出现在任一<b>启用</b> DATAMASK 策略的资源列中即拒绝
   * —— 目标表已有自己的脱敏策略时继承会造成双口径,整批失败,且本检查发生在写
   * 任何东西之前。策略服务保存所有 subject 的策略,引擎的 subject 相对切片看不见
   * 他 subject 的既有策略,只有这里能可靠拒绝。 */
  private static void requireNoTargetConflicts(List<PolicyDto> policies,
      Map<String, TableGroup> byTable) {
    for (PolicyDto policy : policies) {
      if (!enabledDataMask(policy) || policy.resource() == null
          || policy.resource().columns() == null) {
        continue;
      }
      TableGroup group = byTable.get(key(policy.resource().catalog(),
          policy.resource().schema(), policy.resource().table()));
      if (group == null) {
        continue;
      }
      for (String column : policy.resource().columns()) {
        for (InheritedColumn target : group.columns()) {
          if (normalize(column).equals(normalize(target.targetColumn()))) {
            throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
                "复制继承被拒绝:目标表列 "
                    + key(policy.resource().catalog(), policy.resource().schema(),
                        policy.resource().table()) + "." + normalize(column)
                    + " 已有自己的脱敏策略(策略 '" + policy.name() + "')");
          }
        }
      }
    }
  }

  /** 一条可继承的完整脱敏指令:来自全量策略视角的一条启用 DATAMASK 策略,
   * subjects + udf + arguments 原样取自源策略(非引擎按 subject 切片的 items)。 */
  private record InheritedItem(Set<String> users, Set<String> groups, String udf,
      List<Object> arguments) {
  }

  /** 继承条目在 {@link #collectInheritableItems} 结果映射中的键(目标表 + 目标列)。 */
  private static String itemKey(InheritedColumn column) {
    return key(column.targetCatalog(), column.targetSchema(), column.targetTable())
        + "." + normalize(column.targetColumn());
  }

  /** 对每个继承条目,从全量策略中筛出启用 DATAMASK 且资源匹配<b>源列</b>的策略,
   * 收集其完整 subjects+udf+arguments 作为目标表策略内容。某源列在全量策略中无
   * 任何启用 DATAMASK 策略即抛错(fail-closed):引擎看到的 subject 切片不足以
   * 安全注册,静默跳过会留下"干净数据写入但读取不脱敏"的目标列。 */
  private static Map<String, List<InheritedItem>> collectInheritableItems(
      List<PolicyDto> policies, List<InheritedColumn> inherited) {
    Map<String, List<InheritedItem>> byItem = new LinkedHashMap<>();
    for (InheritedColumn column : inherited) {
      var sourceKey = column.source() == null ? null : column.source().key();
      if (sourceKey == null) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "复制继承被拒绝:继承条目缺少来源列,无法从全量策略中收集可继承的完整策略");
      }
      String sourceTable = key(sourceKey.catalog(), sourceKey.schema(), sourceKey.table());
      String sourceColumn = normalize(sourceKey.column());
      List<InheritedItem> items = new ArrayList<>();
      for (PolicyDto policy : policies) {
        if (!enabledDataMask(policy) || policy.resource() == null
            || policy.resource().columns() == null) {
          continue;
        }
        if (!key(policy.resource().catalog(), policy.resource().schema(),
            policy.resource().table()).equals(sourceTable)) {
          continue;
        }
        boolean resourceCoversSource = policy.resource().columns().stream()
            .anyMatch(c -> normalize(c).equals(sourceColumn));
        if (!resourceCoversSource) {
          continue;
        }
        Set<String> users = policy.subjects() == null || policy.subjects().users() == null
            ? Set.of() : policy.subjects().users();
        Set<String> groups = policy.subjects() == null || policy.subjects().groups() == null
            ? Set.of() : policy.subjects().groups();
        items.add(new InheritedItem(users, groups, policy.udf(),
            policy.arguments() == null ? List.of() : policy.arguments()));
      }
      if (items.isEmpty()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "复制继承被拒绝:源列 " + sourceTable + "." + sourceColumn
                + " 在策略服务中无可继承的完整策略");
      }
      byItem.put(itemKey(column), items);
    }
    return byItem;
  }

  /** GET 现有 tables 并与目标表合并:已存在的表按列合并,新表追加。 */
  private List<TableDto> mergeTables(String instanceName, Map<String, TableGroup> byTable,
      Map<String, InheritedTable> structures, String originUser) {
    HttpResponse<String> response = get(policyBase(instanceName),
        policyService.effectiveAdminApiKey(), originUser);
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
          ? new TableDto(group.catalog(), group.schema(), group.table(),
              columnsOf(group, structures.get(key)))
          : mergeColumns(existing, group, structures.get(key)));
    }
    return List.copyOf(merged.values());
  }

  /** 目标表全部列:优先用引擎输出的完整目标表结构(全部输出列,混合复制时不止
   * 继承列);无完整结构时回落到继承列拼装(向后兼容)。按列名去重。 */
  private static List<ColumnDto> columnsOf(TableGroup group, InheritedTable structure) {
    Map<String, ColumnDto> columns = new LinkedHashMap<>();
    if (structure != null && !structure.columns().isEmpty()) {
      for (InheritedTable.ColumnInfo column : structure.columns()) {
        columns.putIfAbsent(normalize(column.name()),
            new ColumnDto(column.name(), column.type()));
      }
      return List.copyOf(columns.values());
    }
    for (InheritedColumn column : group.columns()) {
      columns.putIfAbsent(normalize(column.targetColumn()),
          new ColumnDto(column.targetColumn(), column.targetColumnType()));
    }
    return List.copyOf(columns.values());
  }

  /** 表已存在(INSERT INTO 既有表):保留既有列,补入缺失的目标表列。 */
  private static TableDto mergeColumns(TableDto existing, TableGroup group,
      InheritedTable structure) {
    Map<String, ColumnDto> columns = new LinkedHashMap<>();
    if (existing.columns() != null) {
      for (ColumnDto column : existing.columns()) {
        columns.put(normalize(column.name()), column);
      }
    }
    for (ColumnDto column : columnsOf(group, structure)) {
      columns.putIfAbsent(normalize(column.name()), column);
    }
    return new TableDto(existing.catalog(), existing.schema(), existing.name(),
        List.copyOf(columns.values()));
  }

  /** 每个继承列一条/多条 dataMask 策略:名称 auto.inherit.<table>.<column>,
   * 多条 items 依次以 .2/.3 后缀区分,优先级按 items 顺序递减(优先级高者在前)。
   * 内容取全量策略视角收集的完整 subjects+udf+arguments——引擎的 subject 切片
   * items 不再用于建策略(他 subject 对源列的既有策略只有全量视角可见)。 */
  private void registerColumnPolicies(String instanceName, InheritedColumn column,
      List<InheritedItem> items, String originUser) {
    if (items == null || items.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "rewrite 继承策略注册失败:继承列 " + itemKey(column) + " 没有可继承的完整策略");
    }
    for (int i = 0; i < items.size(); i++) {
      InheritedItem item = items.get(i);
      String name = "auto.inherit." + column.targetTable() + "." + column.targetColumn()
          + (i == 0 ? "" : "." + (i + 1));
      ResourceDto resource = new ResourceDto(column.targetCatalog(), column.targetSchema(),
          column.targetTable(), List.of(column.targetColumn()), List.of());
      SubjectDto subjects = new SubjectDto(item.users(), item.groups());
      // 目标资源不声明 inheritOnCopy:避免已注册的继承策略再次触发链式注册
      PolicyDto policy = new PolicyDto(name, "dataMask", true, items.size() - 1 - i, resource,
          subjects, item.udf(), item.arguments(), null);
      HttpResponse<String> response = post(policyBase(instanceName) + "/policies", policy,
          policyService.effectiveAdminApiKey(), originUser);
      requireOk(response, "POST /api/instances/" + instanceName + "/policies (" + name + ")");
    }
  }

  /** 合并后的 tables 整体 PUT 到策略服务(整体替换语义)。 */
  private void putTables(String instanceName, List<TableDto> tables, String originUser) {
    HttpResponse<String> response = put(policyBase(instanceName) + "/tables",
        new TablesDto(tables), policyService.effectiveAdminApiKey(), originUser);
    requireOk(response, "PUT /api/instances/" + instanceName + "/tables");
  }

  /** 目标表结构登记进元数据服务:structure 端点是整体替换语义,直接 PUT 目标表会
   * 清掉实例其它表已登记的结构——先调数据面只读快照 GET /api/metadata/instances/{name}
   * (data 面 key),按 catalog/schema/table 合并(既有表保留,目标表缺失则追加、
   * 已存在则跳过不覆盖既有列),再把合并后的完整表列表 PUT 到 structure(admin 面
   * key),与策略服务侧的合并语义对称。 */
  private void putStructure(String instanceName, Map<String, TableGroup> byTable,
      Map<String, InheritedTable> structures, String originUser) {
    HttpResponse<String> snapshot = get(metadataSnapshotBase(instanceName),
        metadataService.apiKey(), originUser);
    requireOk(snapshot, "GET /api/metadata/instances/" + instanceName);
    MetadataSnapshotDto existing = readBody(snapshot.body(), MetadataSnapshotDto.class,
        "rewrite 继承策略注册失败:metadata 服务返回了无法解析的结构快照响应");
    Map<String, TablePayload> merged = new LinkedHashMap<>();
    if (existing.tables() != null) {
      for (TableDto table : existing.tables()) {
        merged.put(key(table.catalog(), table.schema(), table.name()), toPayload(table));
      }
    }
    for (TableGroup group : byTable.values()) {
      String key = key(group.catalog(), group.schema(), group.table());
      merged.putIfAbsent(key, new TablePayload(group.catalog(), group.schema(), group.table(),
          columnsOf(group, structures.get(key)).stream()
              .map(column -> new ColumnPayload(column.name(), column.type()))
              .toList()));
    }
    HttpResponse<String> response = put(metadataBase(instanceName) + "/structure",
        List.copyOf(merged.values()), metadataService.effectiveAdminApiKey(), originUser);
    requireOk(response, "PUT /api/instances/" + instanceName + "/structure");
  }

  // ----- wire helpers -----

  private String policyBase(String instanceName) {
    return trimSlash(policyService.baseUrl()) + "/api/instances/" + encode(instanceName);
  }

  private String metadataBase(String instanceName) {
    return trimSlash(metadataService.baseUrl()) + "/api/meta/instances/" + encode(instanceName);
  }

  /** 数据面只读快照端点(MetadataDataController),与 admin 的 /structure 同一服务基址。 */
  private String metadataSnapshotBase(String instanceName) {
    return trimSlash(metadataService.baseUrl()) + "/api/metadata/instances/"
        + encode(instanceName);
  }

  private HttpResponse<String> get(String url, String apiKey, String originUser) {
    return send(withApiKey(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json"), apiKey, originUser)
        .GET()
        .build());
  }

  private HttpResponse<String> put(String url, Object body, String apiKey, String originUser) {
    return send(withApiKey(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json"), apiKey, originUser)
        .PUT(HttpRequest.BodyPublishers.ofString(toJson(body)))
        .build());
  }

  private HttpResponse<String> post(String url, Object body, String apiKey, String originUser) {
    return send(withApiKey(HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json"), apiKey, originUser)
        .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
        .build());
  }

  /** 所有请求带 X-Api-Key;apiKey 未配置时不加头(镜像 MetadataClient 的守卫)。
   * originUser 非空时带 X-Originating-User,上游的审计事件据此记录真实触发者。 */
  private static HttpRequest.Builder withApiKey(HttpRequest.Builder builder, String apiKey,
      String originUser) {
    if (apiKey != null && !apiKey.isBlank()) {
      builder.header("X-Api-Key", apiKey);
    }
    if (originUser != null && !originUser.isBlank()) {
      builder.header("X-Originating-User", originUser);
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

  private static List<PolicyDto> readPolicies(String body, String what) {
    try {
      return JSON.readValue(body, new TypeReference<List<PolicyDto>>() {
      });
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

  /** 快照既有表(TableDto 形状)映射为 structure 端点的 TablePayload。 */
  private static TablePayload toPayload(TableDto table) {
    List<ColumnDto> columns = table.columns() == null ? List.of() : table.columns();
    return new TablePayload(table.catalog(), table.schema(), table.name(), columns.stream()
        .map(column -> new ColumnPayload(column.name(), column.type()))
        .toList());
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

  /** 数据面只读快照(MetadataDtos.MetadataResponse):tables 复用 TableDto 形状。 */
  record MetadataSnapshotDto(String instance, String dialect, long metadataVersion,
      List<TableDto> tables) {
  }
}
