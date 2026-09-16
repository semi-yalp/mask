package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSearchClientTest {

  private FakeEsServer es;
  private ElasticsearchClient client;

  @BeforeEach
  void start() throws IOException {
    es = FakeEsServer.start();
    client = es.client();
  }

  @AfterEach
  void stop() {
    es.close();
  }

  @Test
  void buildsBoolFilterAndMapsHits() throws Exception {
    es.searchBody.set("""
        {"took":1,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
         "hits":{"total":{"value":1,"relation":"eq"},"max_score":1.0,"hits":[
           {"_index":"mask-audit-2026.09.16","_id":"1","_score":1.0,
            "_source":{"eventType":"REWRITE","outcome":"SUCCESS","actor":{"user":"alice"}}}
         ]}}""");
    AuditSearchClient search = new AuditSearchClient(client, "mask-audit");
    AuditQuery q = new AuditQuery("REWRITE", "SUCCESS", "crm", null, null, "alice",
        Instant.parse("2026-09-16T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"),
        0, 50);
    AuditSearchResult result = search.search(q);
    assertEquals(1, result.total());
    assertEquals("REWRITE", result.events().get(0).get("eventType"));
    assertEquals("alice", ((Map<?, ?>) result.events().get(0).get("actor")).get("user"));

    String body = es.requests("/mask-audit").get(0).body();
    assertTrue(es.requests("/mask-audit").get(0).path().contains("mask-audit-*"));
    assertTrue(body.contains("\"query\""));
    assertTrue(body.contains("\"term\""));
    assertTrue(body.contains("\"eventType\":{\"value\":\"REWRITE\"}"));
    assertTrue(body.contains("\"range\""));
    assertTrue(body.contains("\"from\":0"));
    assertTrue(body.contains("\"size\":50"));
    assertTrue(body.contains("\"order\":\"desc\""));
  }

  @Test
  void nullFiltersAreOmittedFromQuery() throws Exception {
    AuditSearchClient search = new AuditSearchClient(client, "mask-audit");
    search.search(new AuditQuery(null, null, null, null, null, null, null, null, 0, 50));
    String body = es.requests("/mask-audit").get(0).body();
    assertTrue(body.contains("\"match_all\""));
    assertTrue(!body.contains("\"term\""));
  }

  @Test
  void esOutageMapsToUnavailable() throws Exception {
    RestClient deadRest = RestClient.builder(
        org.apache.http.HttpHost.create("http://127.0.0.1:1")).build();
    RestClientTransport transport =
        new RestClientTransport(deadRest, new JacksonJsonpMapper(new ObjectMapper()));
    try {
      AuditSearchClient search = new AuditSearchClient(
          new ElasticsearchClient(transport), "mask-audit");
      assertThrows(AuditSearchUnavailableException.class,
          () -> search.search(new AuditQuery(null, null, null, null,
              null, null, Instant.now(), Instant.now().plusSeconds(60), 0, 10)));
    } finally {
      transport.close();
    }
  }
}
