package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixed-condition search over {@code <prefix>-*} (spec §6): keyword equality
 * filters, a @timestamp range, newest first, offset paging. Any ES outage
 * surfaces as {@link AuditSearchUnavailableException}.
 */
public final class AuditSearchClient {

  private final ElasticsearchClient client;
  private final String indexPrefix;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuditSearchClient(ElasticsearchClient client, String indexPrefix) {
    this.client = client;
    this.indexPrefix = indexPrefix;
  }

  public AuditSearchResult search(AuditQuery q) {
    try {
      SearchResponse<JsonNode> response = client.search(s -> {
        s.index(indexPrefix + "-*")
            .from(q.page() * q.size())
            .size(q.size())
            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Desc)));
        List<Query> filters = new ArrayList<>();
        term(filters, "eventType", q.eventType());
        term(filters, "outcome", q.outcome());
        term(filters, "instance", q.instance());
        term(filters, "resourceType", q.resourceType());
        term(filters, "action", q.action());
        term(filters, "actor.user", q.user());
        if (q.from() != null || q.to() != null) {
          filters.add(timestampRange(q.from(), q.to()));
        }
        if (filters.isEmpty()) {
          s.query(query -> query.matchAll(m -> m));
        } else {
          s.query(query -> query.bool(b -> b.filter(filters)));
        }
        return s;
      }, JsonNode.class);
      long total = response.hits().total() == null
          ? response.hits().hits().size() : response.hits().total().value();
      List<Map<String, Object>> events = new ArrayList<>();
      for (var hit : response.hits().hits()) {
        if (hit.source() != null) {
          events.add(mapper.convertValue(hit.source(),
              new TypeReference<Map<String, Object>>() {
              }));
        }
      }
      return new AuditSearchResult(total, events);
    } catch (IOException | RuntimeException e) {
      if (e instanceof AuditSearchUnavailableException u) {
        throw u;
      }
      throw new AuditSearchUnavailableException(
          "elasticsearch search failed: " + e.getMessage(), e);
    }
  }

  /** Built via withJson: the RangeQuery API shape varies across 8.x minors. */
  private static Query timestampRange(java.time.Instant from, java.time.Instant to) {
    StringBuilder json = new StringBuilder("{\"range\":{\"@timestamp\":{");
    if (from != null) {
      json.append("\"gte\":").append(from.toEpochMilli());
    }
    if (to != null) {
      if (from != null) {
        json.append(',');
      }
      json.append("\"lte\":").append(to.toEpochMilli());
    }
    json.append(",\"format\":\"epoch_millis\"}}}");
    return Query.of(q -> q.withJson(new StringReader(json.toString())));
  }

  private static void term(List<Query> filters, String field, String value) {
    if (value != null && !value.isBlank()) {
      filters.add(Query.of(t -> t.term(term -> term.field(field).value(value))));
    }
  }
}
