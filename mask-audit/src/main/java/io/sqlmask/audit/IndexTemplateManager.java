package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;

/**
 * Idempotently PUTs the audit index template (spec §4.3) at most once per 60s
 * until it succeeds; a template failure never blocks writes (ES falls back to
 * dynamic mapping until the template lands).
 */
public final class IndexTemplateManager {

  private static final Logger log = LoggerFactory.getLogger(IndexTemplateManager.class);
  private static final long RETRY_AFTER_MS = 60_000;

  private final ElasticsearchClient client;
  private final String indexPrefix;
  private volatile boolean ready;
  private volatile boolean attempted;
  private volatile long lastAttempt;

  public IndexTemplateManager(ElasticsearchClient client, String indexPrefix) {
    this.client = client;
    this.indexPrefix = indexPrefix;
  }

  public boolean ensureIfStale(long nowMillis) {
    if (ready || (attempted && nowMillis - lastAttempt < RETRY_AFTER_MS)) {
      return ready;
    }
    attempted = true;
    lastAttempt = nowMillis;
    try {
      client.indices().putIndexTemplate(r -> r.name(indexPrefix)
          .withJson(new StringReader(templateJson(indexPrefix))));
      ready = true;
      log.info("audit: index template '{}' installed", indexPrefix);
      return true;
    } catch (Exception e) {
      log.warn("audit: index template PUT failed, will retry in {}s: {}",
          RETRY_AFTER_MS / 1000, e.getMessage());
      return false;
    }
  }

  static String templateJson(String prefix) {
    return """
        {
          "index_patterns": ["%s-*"],
          "template": {
            "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
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
        }""".formatted(prefix);
  }
}
