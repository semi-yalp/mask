package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexTemplateManagerTest {

  private FakeEsServer es;
  private ElasticsearchClient client;

  @BeforeEach
  void start() throws IOException {
    es = FakeEsServer.start();
    client = es.client();
  }

  @AfterEach
  void stop() throws IOException {
    client._transport().close();
    es.close();
  }

  @Test
  void putsTemplateOnceAndStaysReady() throws Exception {
    IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit");
    assertTrue(m.ensureIfStale(1000));
    assertTrue(m.ensureIfStale(2000)); // ready: no second PUT
    assertEquals(1, es.requests("/_index_template").size());
    String body = es.requests("/_index_template").get(0).body();
    assertTrue(body.contains("\"index_patterns\":[\"mask-audit-*\"]"));
    assertTrue(body.contains("mask-audit-")); // date-math-free pattern prefix
    assertTrue(body.contains("\"@timestamp\""));
    assertTrue(body.contains("\"keyword\""));
  }

  @Test
  void failureRetriesOnlyAfterSixtySeconds() throws Exception {
    ElasticsearchClient dead = deadClient();
    try {
      IndexTemplateManager m = new IndexTemplateManager(dead, "mask-audit");
      long t0 = 1_000_000L;
      assertFalse(m.ensureIfStale(t0));      // fail, records retry-after
      assertFalse(m.ensureIfStale(t0 + 59_000)); // inside window: no attempt
      assertFalse(m.ensureIfStale(t0 + 61_000)); // past window: retries, fails again
    } finally {
      dead._transport().close();
    }
  }

  @Test
  void recoversWhenEsComesBack() throws Exception {
    // Outage: a manager pointed at an unreachable ES fails without throwing.
    ElasticsearchClient dead = deadClient();
    try {
      IndexTemplateManager down = new IndexTemplateManager(dead, "mask-audit");
      assertFalse(down.ensureIfStale(1_000_000L));
    } finally {
      dead._transport().close();
    }
    // ES is back: the template installs on the next stale check, exactly once.
    IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit");
    assertTrue(m.ensureIfStale(1_100_000L));
    assertEquals(1, es.requests("/_index_template").size());
  }

  private ElasticsearchClient deadClient() {
    RestClient rest = RestClient.builder(
        org.apache.http.HttpHost.create("http://127.0.0.1:1")).build();
    return new ElasticsearchClient(new co.elastic.clients.transport.rest_client.RestClientTransport(
        rest, new co.elastic.clients.json.jackson.JacksonJsonpMapper(
            new com.fasterxml.jackson.databind.ObjectMapper())));
  }
}
