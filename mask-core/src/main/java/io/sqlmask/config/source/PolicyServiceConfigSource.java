package io.sqlmask.config.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.server.EffectiveMetrics;

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
import java.util.Map;
import java.util.TreeSet;

/**
 * Pulls the compiled effective configuration from the policy service and
 * caches it per subject (user plus normalized groups) in an access-ordered
 * LRU of at most {@link #MAX_CACHED_SUBJECTS} entries — an evicted subject
 * simply re-fetches on its next load (failing closed if the service is then
 * unreachable). Serves a subject's cached configuration while the service is
 * unreachable (stale-but-available); a subject with no cache entry plus an
 * unreachable service fails closed with POLICY_SERVICE_UNAVAILABLE — never
 * degrades to the unmasked input.
 */
public final class PolicyServiceConfigSource implements ConfigSource {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Cached-subject ceiling; excess evicts least-recently-used. */
  private static final int MAX_CACHED_SUBJECTS = 256;

  private record SubjectKey(String user, List<String> groups) {
  }

  private final HttpClient http;
  private final String baseUrl;
  private final String apiKey;
  private final String instanceName;
  private final EffectiveMetrics metrics;
  private final Map<SubjectKey, ResolvedConfig> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SubjectKey, ResolvedConfig> eldest) {
          return size() > MAX_CACHED_SUBJECTS;
        }
      };

  public PolicyServiceConfigSource(String baseUrl, String apiKey, String instanceName) {
    this(baseUrl, apiKey, instanceName, null);
  }

  /** {@code metrics} may be null (CLI runs without a meter registry). */
  public PolicyServiceConfigSource(String baseUrl, String apiKey, String instanceName,
      EffectiveMetrics metrics) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.instanceName = instanceName;
    this.metrics = metrics;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public synchronized ResolvedConfig load() {
    return load(Subject.anonymous());
  }

  /** Loads (and caches) the effective config compiled for one subject. */
  public synchronized ResolvedConfig load(Subject subject) {
    SubjectKey key = keyOf(subject);
    ResolvedConfig cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    ResolvedConfig fresh = fetchAndAssemble(key);
    cache.put(key, fresh);
    return fresh;
  }

  /** Polls every cached subject; true when any subject's version moved. */
  public synchronized boolean refresh() {
    boolean anyUpdated = false;
    for (Map.Entry<SubjectKey, ResolvedConfig> entry : cache.entrySet()) {
      ResolvedConfig fresh = fetchAndAssemble(entry.getKey());
      if (entry.getValue().configVersion() != fresh.configVersion()) {
        entry.setValue(fresh);
        anyUpdated = true;
      }
    }
    return anyUpdated;
  }

  private static SubjectKey keyOf(Subject subject) {
    return new SubjectKey(subject.user(),
        List.copyOf(new TreeSet<>(subject.groups())));
  }

  private ResolvedConfig fetchAndAssemble(SubjectKey key) {
    long start = System.nanoTime();
    URI effectiveUri = effectiveUri(key);
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
      if (metrics != null) {
        metrics.failure(instanceName, start);
      }
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service unreachable at '" + effectiveUri + "': " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (metrics != null) {
        metrics.failure(instanceName, start);
      }
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "interrupted while calling the policy service", e);
    }
    int code = response.statusCode();
    if (code == 404) {
      if (metrics != null) {
        metrics.notFound(start);
      }
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "policy instance '" + instanceName + "' does not exist on the policy service");
    }
    if (code == 401) {
      if (metrics != null) {
        metrics.failure(instanceName, start);
      }
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy service rejected the configured API key (HTTP 401)");
    }
    if (code != 200) {
      if (metrics != null) {
        metrics.failure(instanceName, start);
      }
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service returned HTTP " + code + " for instance '" + instanceName + "'");
    }
    EffectiveConfigResponse payload;
    try {
      payload = JSON.readValue(response.body(), EffectiveConfigResponse.class);
    } catch (IOException e) {
      if (metrics != null) {
        metrics.failure(instanceName, start);
      }
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service returned an unreadable effective config: " + e.getMessage(), e);
    }
    if (metrics != null) {
      metrics.success(instanceName, payload, start);
    }
    return new ResolvedConfig(
        new EffectiveConfigAssembler().assemble(payload), payload.dialect(), payload.configVersion());
  }

  private URI effectiveUri(SubjectKey key) {
    List<String> params = new ArrayList<>();
    if (key.user() != null) {
      params.add("user=" + URLEncoder.encode(key.user(), StandardCharsets.UTF_8));
    }
    for (String group : key.groups()) {
      params.add("groups=" + URLEncoder.encode(group, StandardCharsets.UTF_8));
    }
    String query = params.isEmpty() ? "" : "?" + String.join("&", params);
    return URI.create(baseUrl + "/api/effective/" + instanceName + query);
  }
}
