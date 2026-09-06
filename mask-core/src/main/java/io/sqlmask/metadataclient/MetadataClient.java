package io.sqlmask.metadataclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

/**
 * Pull primitive over the metadata service data plane. Mirrors
 * PolicyServiceConfigSource's transport discipline (X-Api-Key, mapped status
 * codes, fail-closed on unreachable). Caching/polling/propagation live with
 * the policy compiler, not here.
 */
public final class MetadataClient {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final URI base;
  private final String apiKey;

  public MetadataClient(String baseUrl, String apiKey) {
    this.base = URI.create(baseUrl);
    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /** 200 → version; 404 → empty; 401/other/unreachable → fail-closed exceptions. */
  public OptionalLong versionOf(String instance) {
    HttpResponse<String> response = send(request(URI.create(
        base + "/api/metadata/instances/" + instance + "/version")));
    if (response.statusCode() == 404) {
      return OptionalLong.empty();
    }
    requireOk(response, instance);
    try {
      VersionPayload payload = JSON.readValue(response.body(), VersionPayload.class);
      return OptionalLong.of(payload.metadataVersion());
    } catch (IOException e) {
      throw unavailable("metadata service returned an unreadable version payload", e);
    }
  }

  public MetadataSnapshot fetch(String instance) {
    HttpResponse<String> response = send(request(URI.create(
        base + "/api/metadata/instances/" + instance)));
    if (response.statusCode() == 404) {
      throw new SqlMaskException(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
          "metadata instance '" + instance + "' does not exist on the metadata service");
    }
    requireOk(response, instance);
    try {
      return JSON.readValue(response.body(), MetadataSnapshot.class);
    } catch (IOException e) {
      throw unavailable("metadata service returned an unreadable structure payload", e);
    }
  }

  private HttpRequest request(URI uri) {
    return HttpRequest.newBuilder(uri)
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw unavailable("metadata service unreachable at '" + request.uri() + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("interrupted while calling the metadata service", e);
    }
  }

  private static void requireOk(HttpResponse<String> response, String instance) {
    int code = response.statusCode();
    if (code == 401) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadata service rejected the configured API key (HTTP 401)");
    }
    if (code != 200) {
      throw unavailable("metadata service returned HTTP " + code
          + " for instance '" + instance + "'", null);
    }
  }

  private static SqlMaskException unavailable(String message, Throwable cause) {
    return new SqlMaskException(SqlMaskException.Code.METADATA_SERVICE_UNAVAILABLE, message, cause);
  }

  public record MetadataSnapshot(String instance, String dialect, long metadataVersion,
      List<TableSnapshot> tables) {
  }

  public record TableSnapshot(String catalog, String schema, String name,
      List<ColumnSnapshot> columns) {
  }

  public record ColumnSnapshot(String name, String type) {
  }

  record VersionPayload(String instance, long metadataVersion) {
  }
}
