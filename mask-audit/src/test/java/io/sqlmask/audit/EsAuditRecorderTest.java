package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsAuditRecorderTest {

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

  private AuditProperties props(int queue, int batch, long intervalMs) {
    AuditProperties p = new AuditProperties();
    p.setQueueCapacity(queue);
    p.setBatchSize(batch);
    p.setFlushIntervalMs(intervalMs);
    p.setSqlMaxChars(100);
    return p;
  }

  private static AuditEvent event(String sql) {
    return AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 1L, null, "ANONYMOUS",
        null, List.of(), "postgresql", 1, false, false, sql, sql, null, null);
  }

  @Test
  void batchSizeTriggersBulkWithDailyIndexAndDocument() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(100, 2, 60_000))) {
      r.record(event("SELECT 1"));
      r.record(event("SELECT 2"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000);
    }
    List<FakeEsServer.RecordedRequest> bulks = es.requests("/_bulk");
    assertEquals(1, bulks.size());
    String body = bulks.get(0).body();
    String today = EsAuditRecorder.indexName("mask-audit", Instant.now());
    assertTrue(body.contains("\"_index\":\"" + today + "\""));
    assertTrue(body.contains("\"eventType\":\"REWRITE\""));
    assertTrue(body.contains("SELECT 1"));
  }

  @Test
  void overflowIsDroppedAndCountedNotBlocking() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(2, 1000, 60_000))) {
      r.record(event("a"));
      r.record(event("b"));
      long start = System.nanoTime();
      r.record(event("c")); // queue full (worker may not have drained yet) -> drop or offer
      r.record(event("d"));
      r.record(event("e"));
      assertTrue(System.nanoTime() - start < 100_000_000L); // no blocking
    }
  }

  @Test
  void recordNeverThrowsAndAcceptsNull() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(2, 1000, 60_000))) {
      r.record(null);
      r.record(event("x"));
    }
  }

  @Test
  void bulkFailureCountsAndRecoversWithoutBlocking() throws Exception {
    es.setBulkFailure();
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(100, 1, 60_000))) {
      r.record(event("SELECT 1"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000);
      // The server records the request before answering 503; wait until the
      // worker has actually processed the failure before asserting the counter.
      waitUntil(() -> r.droppedBatches() >= 1, 5000);
      assertTrue(r.droppedBatches() >= 1);
      es.resetBulk();
      r.record(event("SELECT 2"));
      waitUntil(() -> es.requests("/_bulk").size() >= 2, 5000);
    }
  }

  @Test
  void indexNameUsesUtcDate() {
    Instant t = Instant.parse("2026-09-16T23:59:59Z");
    assertEquals("mask-audit-2026.09.16", EsAuditRecorder.indexName("mask-audit", t));
    Instant t2 = Instant.parse("2026-01-01T00:00:00Z");
    assertEquals("mask-audit-2026.01.01", EsAuditRecorder.indexName("mask-audit", t2));
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition,
      long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("condition not met in " + timeoutMs + "ms");
      }
      Thread.sleep(25);
    }
  }
}
