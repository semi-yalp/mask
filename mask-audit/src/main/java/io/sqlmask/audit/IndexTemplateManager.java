package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;

/**
 * Idempotently PUTs the audit index template (spec §4.3), re-checking at most
 * once per 60s forever: after the first success a later failed PUT (template
 * deleted on the ES side) flips back to not-ready and retries — a template
 * failure never blocks writes (ES falls back to dynamic mapping until the
 * template lands).
 */
public final class IndexTemplateManager {

  private static final Logger log = LoggerFactory.getLogger(IndexTemplateManager.class);
  private static final long RETRY_AFTER_MS = 60_000;

  private final ElasticsearchClient client;
  private final String indexPrefix;
  private final int replicas;
  private volatile boolean ready;
  /** Half of MIN_VALUE: any real timestamp reads as "retry due", so the very
   * first call always attempts the PUT. */
  private volatile long lastAttempt = Long.MIN_VALUE / 2;

  public IndexTemplateManager(ElasticsearchClient client, String indexPrefix, int replicas) {
    if (client == null || indexPrefix == null || !indexPrefix.matches("[A-Za-z0-9._\\-]+")) {
      throw new IllegalArgumentException(
          "audit index prefix must match [A-Za-z0-9._-]+ (got '" + indexPrefix + "')");
    }
    this.client = client;
    this.indexPrefix = indexPrefix;
    this.replicas = replicas;
  }

  public boolean ensureIfStale(long nowMillis) {
    if (nowMillis - lastAttempt < RETRY_AFTER_MS) {
      return ready;
    }
    lastAttempt = nowMillis;
    try {
      client.indices().putIndexTemplate(r -> r.name(indexPrefix)
          .withJson(new StringReader(templateJson(indexPrefix, replicas))));
      boolean wasReady = ready;
      ready = true;
      if (!wasReady) {
        log.info("audit: index template '{}' installed", indexPrefix);
      }
      return true;
    } catch (Exception e) {
      if (ready) {
        ready = false;
        log.warn("audit: index template re-check failed (deleted on the ES side?), "
            + "will retry in {}s: {}", RETRY_AFTER_MS / 1000, e.getMessage());
      } else {
        log.warn("audit: index template PUT failed, will retry in {}s: {}",
            RETRY_AFTER_MS / 1000, e.getMessage());
      }
      return false;
    }
  }

  static String templateJson(String prefix, int replicas) {
    return """
        {
          "index_patterns": ["%s-*"],
          "template": {
            "settings": { "number_of_shards": 1, "number_of_replicas": %d },
            "mappings": {
              "properties": {
                "@timestamp": {"type": "date"},
                "eventType": {"type": "keyword"},
                "service": {"type": "keyword"},
                "outcome": {"type": "keyword"},
                "durationMs": {"type": "integer"},
                "sourceIp": {"type": "ip"},
                "actor": {
                  "properties": {
                    "user": {"type": "keyword"},
                    "groups": {"type": "keyword"},
                    "authKind": {"type": "keyword"}
                  }
                },
                "error": {
                  "properties": {
                    "code": {"type": "keyword"},
                    "message": {"type": "text"}
                  }
                },
                "dialect": {"type": "keyword"},
                "statementCount": {"type": "integer"},
                "masked": {"type": "boolean"},
                "rowFiltered": {"type": "boolean"},
                "originalSql": {"type": "text", "fields": {"keyword":
                  {"type": "keyword", "ignore_above": 256}}},
                "rewrittenSql": {"type": "text", "fields": {"keyword":
                  {"type": "keyword", "ignore_above": 256}}},
                "sqlTruncated": {"type": "boolean"},
                "resourceType": {"type": "keyword"},
                "action": {"type": "keyword"},
                "instance": {"type": "keyword"},
                "resourceName": {"type": "keyword"},
                "detail": {"type": "object"}
              }
            }
          }
        }""".formatted(prefix, replicas);
  }
}
