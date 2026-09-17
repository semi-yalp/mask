package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsAuditRecorderTest {

  private FakeEsServer es;
  private ElasticsearchClient client;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @BeforeEach
  void start() throws IOException {
    es = FakeEsServer.start();
    client = es.client();
  }

  @AfterEach
  void stop() {
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

  private static AuditEvent adminEvent() {
    return AuditEvent.adminChange("sql-mask", AuditEvent.SUCCESS, 1L, null, null,
        "CREATE", "POLICY", "inst-1", "policy-1", null, null, null);
  }

  private static AuditEvent pullEvent() {
    return AuditEvent.effectivePull("sql-mask", AuditEvent.SUCCESS, 1L, null, null,
        "alice", List.of("dba"), "inst-1", null, null);
  }

  @Test
  void batchSizeTriggersBulkWithDailyIndexAndDocument() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(100, 2, 60_000), registry)) {
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
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(2, 1000, 60_000), registry)) {
      r.record(event("a"));
      r.record(event("b"));
      long start = System.nanoTime();
      r.record(event("c")); // queue full (worker may not have drained yet) -> drop or offer
      r.record(event("d"));
      r.record(event("e"));
      assertTrue(System.nanoTime() - start < 1_000_000_000L); // no blocking
    }
  }

  @Test
  void recordNeverThrowsAndAcceptsNull() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(2, 1000, 60_000), registry)) {
      r.record(null);
      r.record(event("x"));
    }
  }

  @Test
  void bulkFailureCountsAndRecoversWithoutBlocking() throws Exception {
    es.setBulkFailure();
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(100, 1, 60_000), registry)) {
      r.record(event("SELECT 1"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty() && r.droppedBatches() >= 1, 5000);
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

  @Test
  void successfulOfferCountsEnqueuedPerEventTypeAndExposesQueueGauges() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    es.bulkGate.set(gate);
    EsAuditRecorder r = new EsAuditRecorder(client, props(3, 1, 60_000), registry);
    try {
      r.record(event("SELECT 1"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000); // worker holds the first event
      r.record(adminEvent());
      r.record(pullEvent());
      assertEquals(1.0, registry.get("sqlmask.audit.enqueued")
          .tag("event_type", "REWRITE").counter().count());
      assertEquals(1.0, registry.get("sqlmask.audit.enqueued")
          .tag("event_type", "ADMIN_CHANGE").counter().count());
      assertEquals(1.0, registry.get("sqlmask.audit.enqueued")
          .tag("event_type", "EFFECTIVE_PULL").counter().count());
      assertEquals(2.0, registry.get("sqlmask.audit.queue.depth").gauge().value());
      assertEquals(3.0, registry.get("sqlmask.audit.queue.capacity").gauge().value());
    } finally {
      gate.countDown();
      r.close();
    }
  }

  @Test
  void queueFullDropCountsDroppedQueueFullWithoutBlocking() throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    es.bulkGate.set(gate);
    EsAuditRecorder r = new EsAuditRecorder(client, props(3, 1, 60_000), registry);
    try {
      r.record(event("a"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000); // worker holds "a", cannot poll
      long start = System.nanoTime();
      r.record(event("b"));
      r.record(event("c"));
      r.record(event("d"));
      r.record(event("e")); // queue b,c,d full -> e dropped
      assertTrue(System.nanoTime() - start < 1_000_000_000L); // no blocking
      assertEquals(1.0, registry.get("sqlmask.audit.dropped")
          .tag("reason", "QUEUE_FULL").counter().count());
      assertEquals(4.0, registry.get("sqlmask.audit.enqueued")
          .tag("event_type", "REWRITE").counter().count());
      assertEquals(3.0, registry.get("sqlmask.audit.queue.depth").gauge().value());
    } finally {
      gate.countDown();
      r.close();
    }
  }

  @Test
  void bulkClientFailureCountsFailureBatchAndDropsEvents() throws Exception {
    es.setBulkFailure();
    EsAuditRecorder r = new EsAuditRecorder(client, props(10, 2, 60_000), registry);
    try {
      r.record(event("SELECT 1"));
      r.record(event("SELECT 2"));
      waitUntil(() -> r.droppedBatches() >= 1, 5000);
      assertEquals(1.0, registry.get("sqlmask.audit.es.batches")
          .tag("outcome", "FAILURE").counter().count());
      assertEquals(2.0, registry.get("sqlmask.audit.dropped")
          .tag("reason", "ES_FAILURE").counter().count());
      assertEquals(0, registry.find("sqlmask.audit.es.documents").counters().size());
      assertEquals(1.0, registry.get("sqlmask.audit.es.write").timer().count());
    } finally {
      r.close();
    }
  }

  @Test
  void bulkPartialErrorResponseCountsFailureBatchAndDropsEvents() throws Exception {
    es.bulkBody.set("{\"took\":1,\"errors\":true,\"items\":[]}");
    EsAuditRecorder r = new EsAuditRecorder(client, props(10, 2, 60_000), registry);
    try {
      r.record(event("SELECT 1"));
      r.record(event("SELECT 2"));
      waitUntil(() -> r.droppedBatches() >= 1, 5000);
      assertEquals(1.0, registry.get("sqlmask.audit.es.batches")
          .tag("outcome", "FAILURE").counter().count());
      assertEquals(2.0, registry.get("sqlmask.audit.dropped")
          .tag("reason", "ES_FAILURE").counter().count());
      assertEquals(0, registry.find("sqlmask.audit.es.documents").counters().size());
      assertEquals(1.0, registry.get("sqlmask.audit.es.write").timer().count());
    } finally {
      r.close();
    }
  }

  @Test
  void bulkSuccessCountsBatchesDocumentsAndWriteTimerWithSloBuckets() throws Exception {
    EsAuditRecorder r = new EsAuditRecorder(client, props(10, 2, 60_000), registry);
    try {
      r.record(event("SELECT 1"));
      r.record(event("SELECT 2"));
      waitUntil(() -> {
        Counter documents = registry.find("sqlmask.audit.es.documents").counter();
        return documents != null && !es.requests("/_bulk").isEmpty()
            && documents.count() >= 2.0;
      }, 5000);
      assertEquals(1.0, registry.get("sqlmask.audit.es.batches")
          .tag("outcome", "SUCCESS").counter().count());
      assertEquals(2.0, registry.get("sqlmask.audit.es.documents").counter().count());
      assertEquals(0, registry.find("sqlmask.audit.es.batches")
          .tag("outcome", "FAILURE").counters().size());
      assertEquals(0, registry.find("sqlmask.audit.dropped").counters().size());
      Timer timer = registry.get("sqlmask.audit.es.write").timer();
      assertEquals(1.0, timer.count());
      assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
      assertEquals(11, timer.takeSnapshot().histogramCounts().length); // spec §3.5 SLO 桶
    } finally {
      r.close();
    }
  }

  @Test
  void closeDrainsAtMostFiveSecondsThenCountsRemainingAsShutdownDrops() throws Exception {
    es.bulkDelayMs.set(2000);
    EsAuditRecorder r = new EsAuditRecorder(client, props(10, 1, 60_000), registry);
    long start = 0;
    try {
      r.record(event("a"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000); // worker holds "a" in a slow bulk
      r.record(event("b"));
      r.record(event("c"));
      r.record(event("d"));
      r.record(event("e"));
      start = System.currentTimeMillis();
    } finally {
      r.close(); // always close, even if the setup or assertions above fail
    }
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed >= 4_000, "close should honor the 5s drain window, took " + elapsed + "ms");
    assertTrue(elapsed < 15_000, "close should not wait much beyond 5s, took " + elapsed + "ms");
    assertTrue(registry.get("sqlmask.audit.dropped")
        .tag("reason", "SHUTDOWN").counter().count() >= 1.0);
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
