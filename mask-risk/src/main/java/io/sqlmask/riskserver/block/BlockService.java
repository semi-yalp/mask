package io.sqlmask.riskserver.block;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.riskserver.config.RiskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alert-driven blocking: pushes ROW_FILTER policies ("WHERE 1 = 0") for the
 * offending user into the policy service's management API — the enforcement
 * loop that turns a detected risk into an actual access cut-off. Idempotent:
 * re-blocking a user refreshes nothing; unblocking deletes exactly the
 * policies this service created ({@code risk-block-*}).
 */
public class BlockService {

  private static final Logger log = LoggerFactory.getLogger(BlockService.class);
  public static final String POLICY_PREFIX = "risk-block-";
  private static final int BLOCK_PRIORITY = 10_000;

  private final RiskProperties.Block config;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .build();
  private final ObjectMapper mapper = new ObjectMapper()
      .findAndRegisterModules()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final java.nio.file.Path stateFile;

  /** user -> active block state (instance, policy names, timestamps). */
  private final Map<String, BlockState> active = new ConcurrentHashMap<>();

  /** One active block. */
  public record BlockState(String user, String instance, List<String> policyNames,
      long blockedAt, long lastAlertId, String note) {
  }

  /** Result of one block/unblock call. */
  public record BlockResult(String user, String instance, List<String> policies,
      boolean alreadyBlocked, String verification) {
  }

  public BlockService(RiskProperties.Block config) {
    this.config = config;
    this.stateFile = config.getStatePath() == null || config.getStatePath().isBlank()
        ? null : java.nio.file.Path.of(config.getStatePath());
    restoreState();
  }

  public boolean configured() {
    return config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
  }

  /**
   * Blocks {@code user} on every table of {@code instance} by creating one
   * ROW_FILTER policy per table with {@code 1 = 0}.
   */
  public BlockResult block(String user, String instance, String note) {
    requireConfigured();
    if (user == null || user.isBlank()) {
      throw new IllegalArgumentException("user is required");
    }
    String target = instance == null || instance.isBlank()
        ? config.getDefaultInstance() : instance;

    BlockState existing = active.get(user);
    List<Map<String, Object>> tables = instanceTables(target);
    List<String> created = new ArrayList<>();
    for (Map<String, Object> table : tables) {
      String policyName = policyName(user, tableName(table));
      if (policyExists(target, policyName)) {
        created.add(policyName);
        continue;
      }
      createBlockPolicy(target, policyName, user, table);
      created.add(policyName);
    }
    long now = System.currentTimeMillis();
    String alertRef = note == null ? "" : note;
    active.put(user, new BlockState(user, target, List.copyOf(created), now, now, alertRef));
    saveState();
    String verification = verifyEffective(target, user);
    log.info("risk: blocked user {} on instance {} via {} policy(ies)", user, target, created.size());
    return new BlockResult(user, target, created, existing != null, verification);
  }

  /** Removes every {@code risk-block-*} policy this service created for the user. */
  public BlockResult unblock(String user, String instance) {
    requireConfigured();
    if (user == null || user.isBlank()) {
      throw new IllegalArgumentException("user is required");
    }
    String target = instance == null || instance.isBlank()
        ? config.getDefaultInstance() : instance;
    BlockState state = active.get(user);
    List<String> removed = new ArrayList<>();
    List<String> candidates = state != null && state.instance().equals(target)
        ? state.policyNames()
        : blockPolicyNames(target, user);
    for (String policyName : candidates) {
      if (deletePolicy(target, policyName)) {
        removed.add(policyName);
      }
    }
    active.remove(user);
    saveState();
    String verification = verifyEffective(target, user);
    log.info("risk: unblocked user {} on instance {} (removed {})", user, target, removed.size());
    return new BlockResult(user, target, removed, false, verification);
  }

  /** Current active blocks (for the console). */
  public List<BlockState> snapshot() {
    return List.copyOf(active.values());
  }

  public boolean isBlocked(String user) {
    return user != null && active.containsKey(user);
  }

  // ---- local state persistence (the policy list itself lives in the policy service) ----

  private void saveState() {
    if (stateFile == null) {
      return;
    }
    try {
      if (stateFile.getParent() != null) {
        java.nio.file.Files.createDirectories(stateFile.getParent());
      }
      mapper.writerWithDefaultPrettyPrinter()
          .writeValue(stateFile.toFile(), List.copyOf(active.values()));
    } catch (Exception e) {
      log.warn("risk: block-state save to {} failed: {}", stateFile, e.toString());
    }
  }

  private void restoreState() {
    if (stateFile == null || !java.nio.file.Files.exists(stateFile)) {
      return;
    }
    try {
      List<BlockState> states = Arrays.asList(
          mapper.readValue(stateFile.toFile(), BlockState[].class));
      states.forEach(state -> active.put(state.user(), state));
      if (!states.isEmpty()) {
        log.info("risk: restored {} active block(s) from {}", states.size(), stateFile);
      }
    } catch (Exception e) {
      log.warn("risk: block-state file {} unreadable ({}), starting empty",
          stateFile, e.toString());
    }
  }

  // ---- policy-service HTTP ----

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> instanceTables(String instance) {
    JsonNode root = call("GET", "/api/instances/" + instance, null);
    if (root == null || !root.has("tables") || !root.get("tables").isArray()) {
      throw new IllegalStateException("instance '" + instance + "' not found or has no tables");
    }
    List<Map<String, Object>> tables = new ArrayList<>();
    for (JsonNode table : root.get("tables")) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("catalog", text(table, "catalog"));
      row.put("schema", text(table, "schema"));
      row.put("name", text(table, "name"));
      tables.add(row);
    }
    return tables;
  }

  private void createBlockPolicy(String instance, String policyName, String user,
      Map<String, Object> table) {
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("name", policyName);
    policy.put("policyType", "ROW_FILTER");
    policy.put("isEnabled", true);
    policy.put("priority", BLOCK_PRIORITY);
    policy.put("resource", Map.of(
        "catalog", table.get("catalog"),
        "schema", table.get("schema"),
        "table", table.get("name"),
        "columns", List.of()));
    policy.put("subjects", Map.of("users", List.of(user), "groups", List.of()));
    policy.put("filterExpr", "1 = 0");
    call("POST", "/api/instances/" + instance + "/policies", policy);
  }

  private boolean policyExists(String instance, String policyName) {
    return blockPolicyNames(instance, null).contains(policyName)
        || findPolicy(instance, policyName) != null;
  }

  private JsonNode findPolicy(String instance, String policyName) {
    JsonNode list = call("GET", "/api/instances/" + instance + "/policies", null);
    if (list == null || !list.isArray()) {
      return null;
    }
    for (JsonNode policy : list) {
      if (policyName.equals(text(policy, "name"))) {
        return policy;
      }
    }
    return null;
  }

  /** All {@code risk-block-} policies on the instance, optionally one user's. */
  private List<String> blockPolicyNames(String instance, String user) {
    JsonNode list = call("GET", "/api/instances/" + instance + "/policies", null);
    List<String> names = new ArrayList<>();
    if (list == null || !list.isArray()) {
      return names;
    }
    String prefix = user == null ? POLICY_PREFIX : POLICY_PREFIX + user + "-";
    for (JsonNode policy : list) {
      String name = text(policy, "name");
      if (name != null && name.startsWith(prefix)) {
        names.add(name);
      }
    }
    return names;
  }

  private boolean deletePolicy(String instance, String policyName) {
    try {
      HttpRequest.Builder request = base("/api/instances/" + instance + "/policies/"
          + urlEncode(policyName)).DELETE();
      HttpResponse<String> response = http.send(request.build(),
          HttpResponse.BodyHandlers.ofString());
      return response.statusCode() / 100 == 2;
    } catch (IOException | InterruptedException | RuntimeException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("policy delete failed: " + e.getMessage(), e);
    }
  }

  /**
   * Reads the compiled effective config for the user and counts the injected
   * row-filter rules - the proof that blocking actually reaches the data plane.
   */
  private String verifyEffective(String instance, String user) {
    try {
      JsonNode effective = call("GET", "/api/effective/" + instance + "?user="
          + urlEncode(user), null);
      int filters = 0;
      if (effective != null && effective.has("rowFilters")) {
        filters = effective.get("rowFilters").size();
      } else if (effective != null) {
        filters = countKeyRecursively(effective, "rowFilter");
      }
      return filters > 0
          ? String.format("生效配置验证:用户 %s 的行过滤规则 %d 条已编译下发", user, filters)
          : String.format("生效配置验证:未发现行过滤规则(用户 %s 可能无匹配主体)", user);
    } catch (RuntimeException e) {
      return "生效配置验证失败: " + e.getMessage();
    }
  }

  private static int countKeyRecursively(JsonNode node, String key) {
    int count = 0;
    if (node == null) {
      return 0;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> entry : iterable(node.fields())) {
        if (entry.getKey().equalsIgnoreCase(key) && !entry.getValue().isNull()) {
          count++;
        }
        count += countKeyRecursively(entry.getValue(), key);
      }
    } else if (node.isArray()) {
      for (JsonNode item : node) {
        count += countKeyRecursively(item, key);
      }
    }
    return count;
  }

  private static Iterable<Map.Entry<String, JsonNode>> iterable(
      java.util.Iterator<Map.Entry<String, JsonNode>> fields) {
    return () -> fields;
  }

  private JsonNode call(String method, String path, Object body) {
    try {
      HttpRequest.Builder request = base(path);
      switch (method) {
        case "GET" -> request.GET();
        case "POST" -> request.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        default -> throw new IllegalArgumentException("method " + method);
      }
      HttpResponse<String> response = http.send(request.build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(String.format(
            "policy service %s %s responded %d: %s", method, path,
            response.statusCode(), truncate(response.body())));
      }
      String json = response.body();
      return json == null || json.isBlank() ? mapper.nullNode() : mapper.readTree(json);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("policy service unreachable ("
          + config.getBaseUrl() + "): " + e.getMessage(), e);
    }
  }

  private HttpRequest.Builder base(String path) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(
            URI.create(config.getBaseUrl() + path))
        .timeout(Duration.ofSeconds(5));
    String key = config.getApiKey();
    if (key != null && !key.isBlank()) {
      builder.header("X-Api-Key", key);
    }
    return builder;
  }

  private void requireConfigured() {
    if (!configured()) {
      throw new IllegalArgumentException(
          "blocking is not configured: set risk.block.base-url to the policy service");
    }
  }

  private static String policyName(String user, String table) {
    return POLICY_PREFIX + sanitize(user) + "-" + sanitize(table);
  }

  private static String sanitize(String s) {
    return s == null ? "" : s.replaceAll("[^A-Za-z0-9_-]", "_");
  }

  private static String urlEncode(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String tableName(Map<String, Object> table) {
    return String.valueOf(table.get("name"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200) + "…";
  }
}
