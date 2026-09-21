package io.sqlmask.query.metadata;

import com.fasterxml.jackson.databind.JsonNode;
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

/** Pulls one instance view from the metadata service admin plane. Fail-closed:
 * anything but a 200 detail payload becomes an exception; the password never
 * leaves this call chain (it is read later, only for building the JDBC
 * connection). */
public final class MetadataServiceClient {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http;
  private final URI base;
  private final String apiKey;

  public MetadataServiceClient(String baseUrl, String apiKey) {
    this.base = URI.create(baseUrl);
    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public InstanceView fetch(String instance) {
    // 实例名是用户可见标识（非凭据，可回显）：先编码为合法路径段再拼 URI
    String encoded = URLEncoder.encode(instance, StandardCharsets.UTF_8);
    URI uri;
    try {
      uri = URI.create(base + "/api/instances/" + encoded);
    } catch (IllegalArgumentException e) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "instance name is not a usable path segment: '" + instance + "'", e);
    }
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "metadata service unreachable at '" + request.uri() + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "interrupted while calling the metadata service", e);
    }
    if (response.statusCode() == 404) {
      throw new QueryException(QueryException.INSTANCE_NOT_FOUND,
          "instance '" + instance + "' does not exist on the metadata service");
    }
    if (response.statusCode() == 401) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "metadata service rejected the configured API key (HTTP 401)");
    }
    if (response.statusCode() != 200) {
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "metadata service returned HTTP " + response.statusCode()
              + " for instance '" + instance + "'");
    }
    try {
      JsonNode node = JSON.readTree(response.body());
      JsonNode connection = node.get("connection");
      return new InstanceView(
          node.path("name").asText(instance),
          effectiveEngine(node.path("engine").isMissingNode() ? null : textOrNull(node.get("engine")),
              node.path("dialect").asText(null)),
          node.path("dialect").asText(null),
          node.path("metadataVersion").asLong(0),
          connection == null || connection.isNull() ? null : new ConnectionView(
              connection.path("host").asText(),
              connection.path("port").asInt(),
              connection.path("database").asText(),
              connection.path("dbUser").asText(),
              connection.path("passwordRef").asText(),
              connection.path("sslmode").asText("disable"),
              connection.path("connectTimeoutSeconds").asInt(10)));
    } catch (IOException e) {
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "metadata service returned an unreadable instance payload", e);
    }
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  static String effectiveEngine(String engine, String dialect) {
    if (engine != null && !engine.isBlank()) {
      return engine;
    }
    return switch (dialect == null ? "" : dialect) {
      case "postgresql" -> "postgresql";
      case "trino" -> "trino";
      case "hive" -> "hive";
      case "sparksql" -> "sparksql";
      default -> "mysql";
    };
  }

  public record InstanceView(String name, String engine, String dialect, long metadataVersion,
      ConnectionView connection) {}

  public record ConnectionView(String host, int port, String database, String dbUser,
      String passwordRef, String sslmode, int connectTimeoutSeconds) {}
}
