package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
  void stop() {
    es.close();
  }

  @Test
  void putsTemplateOnceAndStaysReady() throws Exception {
    IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit", 1);
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
    es.setTemplateFailure();
    IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit", 1);
    long t0 = 1_000_000L;
    assertFalse(m.ensureIfStale(t0));                       // fail, records retry-after
    assertFalse(m.ensureIfStale(t0 + 59_000));              // inside window: no attempt
    assertEquals(1, es.requests("/_index_template").size());
    assertFalse(m.ensureIfStale(t0 + 61_000));              // past window: retries, fails again
    assertEquals(2, es.requests("/_index_template").size());
  }

  @Test
  void recoversWhenEsComesBack() throws Exception {
    es.setTemplateFailure();
    IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit", 1);
    assertFalse(m.ensureIfStale(1_000_000L));
    es.resetTemplate();
    assertTrue(m.ensureIfStale(1_100_000L));                // past retry window: succeeds
    assertEquals(2, es.requests("/_index_template").size());
    // ready 后仍按 60s 周期幂等复查（模板在 ES 侧被删时能自动重装）
    assertTrue(m.ensureIfStale(1_200_000L));
    assertEquals(3, es.requests("/_index_template").size());
    assertTrue(m.ensureIfStale(1_300_000L));
    assertEquals(4, es.requests("/_index_template").size());
  }
}
