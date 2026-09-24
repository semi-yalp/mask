package io.sqlmask.query.rewrite;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.query.error.QueryException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Calls mask-core's instance-scoped rewrite endpoint. Every failure before a
 * usable statement list is marked {@code rewritePhase} so the caller's audit
 * boundary can stay silent (mask-core already emitted REWRITE). */
public final class RewriteServiceClient implements QueryRewriter {

  // mask-core's rewrite payload carries fields beyond the statement views the
  // query path consumes (top-level rewrittenSql, per-statement unchanged); skip
  // them instead of failing the whole parse.
  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final HttpClient http;
  private final URI base;
  private final String apiKey;

  public RewriteServiceClient(String baseUrl, String apiKey) {
    this.base = URI.create(baseUrl);
    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public RewrittenQuery rewrite(String instance, String sql, String user, List<String> groups) {
    String payload;
    try {
      payload = JSON.writeValueAsString(java.util.Map.of(
          "sql", sql, "user", user == null ? "" : user,
          "groups", groups == null ? List.of() : groups));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    // 实例名是用户可见标识（非凭据，可回显）：编码为合法路径段再拼 URI；
    // URLEncoder 是表单编码（空格→+），路径段需要 %20
    String encoded = URLEncoder.encode(instance, StandardCharsets.UTF_8).replace("+", "%20");
    URI uri;
    try {
      uri = URI.create(base + "/api/rewrite/instances/" + encoded);
    } catch (IllegalArgumentException e) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "instance name is not a usable path segment: '" + instance + "'", e);
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "rewrite service unreachable at '" + request.uri() + "'", e, true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "interrupted while calling the rewrite service", e, true);
    }
    if (response.statusCode() >= 400 && response.statusCode() < 500) {
      String code = "CONFIG_ERROR";
      String message = "rewrite service returned HTTP " + response.statusCode();
      try {
        var node = JSON.readTree(response.body());
        if (node.hasNonNull("code")) code = node.get("code").asText();
        if (node.hasNonNull("message")) message = node.get("message").asText();
      } catch (IOException ignored) {
        // keep the HTTP fallback message
      }
      throw new QueryException(code, message, null, true);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "rewrite service returned HTTP " + response.statusCode(), null, true);
    }
    try {
      return JSON.readValue(response.body(), RewrittenQuery.class);
    } catch (IOException e) {
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "rewrite service returned an unreadable payload", e, true);
    }
  }
}
