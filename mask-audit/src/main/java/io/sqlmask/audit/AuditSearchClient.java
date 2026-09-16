package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixed-condition search over {@code <prefix>-*} (spec §6): keyword equality
 * term filters, an optional {@code @timestamp} range (epoch millis), newest
 * first, offset paging. Without keyword filters the query is {@code match_all},
 * still bounded by the range. No query DSL is accepted from callers —
 * conditions come only from {@link AuditQuery} fields. Any ES outage (I/O,
 * transport error, 5xx) surfaces as {@link AuditSearchUnavailableException}.
 */
public final class AuditSearchClient {

  private final ElasticsearchClient client;
  private final String indexPrefix;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuditSearchClient(ElasticsearchClient client, String indexPrefix) {
    this.client = client;
    this.indexPrefix = indexPrefix;
  }

  /**
   * Runs the search and maps the hit sources to generic maps. Only non-blank
   * keyword filters become term queries; with none present the query is
   * {@code match_all} (still bounded by the {@code @timestamp} range when one
   * is given).
   *
   * @throws AuditSearchUnavailableException if Elasticsearch cannot be reached
   *     or answers with an error
   */
  public AuditSearchResult search(AuditQuery q) {
    try {
      SearchResponse<JsonNode> response = client.search(s -> {
        s.index(indexPrefix + "-*")
            .from(q.page() * q.size())
            .size(q.size())
            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Desc)));
        List<Query> terms = new ArrayList<>();
        term(terms, "eventType", q.eventType());
        term(terms, "outcome", q.outcome());
        term(terms, "instance", q.instance());
        term(terms, "resourceType", q.resourceType());
        term(terms, "action", q.action());
        term(terms, "actor.user", q.user());
        Query timeRange = q.from() == null && q.to() == null ? null
            : Query.of(r -> r.range(range -> {
              range.field("@timestamp");
              if (q.from() != null) {
                range.gte(JsonData.of(q.from().toEpochMilli()));
              }
              if (q.to() != null) {
                range.lte(JsonData.of(q.to().toEpochMilli()));
              }
              range.format("epoch_millis");
              return range;
            }));
        if (terms.isEmpty() && timeRange == null) {
          s.query(query -> query.matchAll(m -> m));
        } else {
          List<Query> filters = new ArrayList<>();
          if (terms.isEmpty()) {
            // The time range scopes the search but does not by itself replace
            // the match_all query keyword filters would provide.
            filters.add(Query.of(query -> query.matchAll(m -> m)));
          } else {
            filters.addAll(terms);
          }
          if (timeRange != null) {
            filters.add(timeRange);
          }
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
              new TypeReference<Map<String, Object>>() { }));
        }
      }
      return new AuditSearchResult(total, events);
    } catch (IOException | RuntimeException e) {
      if (e instanceof AuditSearchUnavailableException unavailable) {
        throw unavailable;
      }
      throw new AuditSearchUnavailableException(
          "elasticsearch search failed: " + e.getMessage(), e);
    }
  }

  /** Adds a term filter unless the value is null or blank. */
  private static void term(List<Query> filters, String field, String value) {
    if (value != null && !value.isBlank()) {
      filters.add(Query.of(t -> t.term(term -> term.field(field).value(value))));
    }
  }
}
