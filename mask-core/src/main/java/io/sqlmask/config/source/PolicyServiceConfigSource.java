package io.sqlmask.config.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Pulls the compiled effective configuration from the policy service and
 * caches it by version. Serves the cached configuration while the service is
 * unreachable (stale-but-available); a cold cache plus an unreachable service
 * fails closed with POLICY_SERVICE_UNAVAILABLE — never degrades to the
 * unmasked input.
 */
public final class PolicyServiceConfigSource implements ConfigSource {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final URI effectiveUri;
  private final String apiKey;
  private final String instanceName;
  private volatile ResolvedConfig cache;

  public PolicyServiceConfigSource(String baseUrl, String apiKey, String instanceName) {
    this.effectiveUri = URI.create(baseUrl + "/api/effective/" + instanceName);
    this.apiKey = apiKey;
    this.instanceName = instanceName;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public synchronized ResolvedConfig load() {
    if (cache != null) {
      return cache;
    }
    return cache = fetchAndAssemble();
  }

  /**
   * Polls the service and refreshes the cache when the version moved.
   *
   * @return true when the cache was updated
   */
  public synchronized boolean refresh() {
    ResolvedConfig fresh = fetchAndAssemble();
    if (cache != null && cache.configVersion() == fresh.configVersion()) {
      return false;
    }
    cache = fresh;
    return true;
  }

  private ResolvedConfig fetchAndAssemble() {
    HttpRequest request = HttpRequest.newBuilder(effectiveUri)
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service unreachable at '" + effectiveUri + "': " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "interrupted while calling the policy service", e);
    }
    int code = response.statusCode();
    if (code == 404) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "policy instance '" + instanceName + "' does not exist on the policy service");
    }
    if (code == 401) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy service rejected the configured API key (HTTP 401)");
    }
    if (code != 200) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service returned HTTP " + code + " for instance '" + instanceName + "'");
    }
    EffectiveConfigResponse payload;
    try {
      payload = JSON.readValue(response.body(), EffectiveConfigResponse.class);
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service returned an unreadable effective config: " + e.getMessage(), e);
    }
    return new ResolvedConfig(
        new EffectiveConfigAssembler().assemble(payload), payload.dialect(), payload.configVersion());
  }
}
