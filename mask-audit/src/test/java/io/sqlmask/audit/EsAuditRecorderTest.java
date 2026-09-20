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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  @Test
  void flushIntervalElapsedFlushesSmallBatchAndLoopSurvives() throws Exception {
    // P0, §4.4-1: low-traffic main path. batch-size (100) is never reached
    // with 1-2 events; the 150ms flush-interval timer must drive each flush.
    // The existing suite only exercised the size-reached path (all tests use
    // flushIntervalMs=60_000), so a broken timer condition would strand the
    // events in memory with a green suite. Second record proves the writer
    // loop survives the first timer flush and keeps flushing.
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(10, 100, 150), registry)) {
      r.record(event("SELECT low-traffic-1"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000);
      r.record(event("SELECT low-traffic-2"));
      // both timer flushes must land in ES (the requests list sees the bulk on
      // receipt, before completion — also require the documents counter so the
      // close below interrupts the worker in its poll, not mid-HTTP-call)
      waitUntil(() -> {
        Counter documents = registry.find("sqlmask.audit.es.documents").counter();
        return es.requests("/_bulk").size() >= 2
            && documents != null && documents.count() >= 2.0;
      }, 5000);
      assertEquals(2.0, registry.get("sqlmask.audit.es.documents").counter().count());
    }
    List<FakeEsServer.RecordedRequest> bulks = es.requests("/_bulk");
    assertEquals(2, bulks.size());
    // each timer flush pushed exactly one event: neither merged into one batch
    assertTrue(bulks.get(0).body().contains("SELECT low-traffic-1"));
    assertFalse(bulks.get(0).body().contains("SELECT low-traffic-2"));
    assertTrue(bulks.get(1).body().contains("SELECT low-traffic-2"));
    assertEquals(1, occurrences(bulks.get(0).body(), "\"eventType\""));
    assertEquals(1, occurrences(bulks.get(1).body(), "\"eventType\""));
    assertEquals(0, registry.find("sqlmask.audit.dropped").counters().size());
  }

  @Test
  void closeDrainsQueuedBacklogWithinTimeoutWithoutShutdownDrops() throws Exception {
    // P1, §4.4-2: close() draining branch. While the worker is held in a bulk
    // call via the gate the queue fills with a backlog; releasing the gate and
    // closing must empty the queue into ES (bulk requests land) and must not
    // count SHUTDOWN drops. The existing close test only covers the 5s-timeout
    // discard path. Note: close() must not interrupt the worker mid-HTTP-call
    // (the interrupt would abort the in-flight bulk and miscount it as
    // ES_FAILURE), so we first wait until the held bulk and the two full drain
    // batches have landed and the worker is parked on the last partial batch.
    CountDownLatch gate = new CountDownLatch(1);
    es.bulkGate.set(gate);
    EsAuditRecorder r = new EsAuditRecorder(client, props(100, 2, 60_000), registry);
    long start;
    try {
      r.record(event("SELECT hold-a"));
      r.record(event("SELECT hold-b"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000); // worker stuck mid-flush
      for (int i = 0; i < 5; i++) {
        r.record(event("SELECT drain-" + i));
      }
      start = System.currentTimeMillis();
    } finally {
      gate.countDown(); // release the held bulk
      // hold-bulk + drain batches (2,2) in ES and the worker back in its poll
      // with only the last partial batch (drain-4) pending
      waitUntil(() -> {
        Counter documents = registry.find("sqlmask.audit.es.documents").counter();
        return documents != null && documents.count() >= 6.0
            && es.requests("/_bulk").size() >= 3;
      }, 5000);
      r.close(); // interrupts the worker in poll; it drains the final batch
    }
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed < 5_000, "close must drain within the 5s budget, took " + elapsed + "ms");
    List<FakeEsServer.RecordedRequest> bulks = es.requests("/_bulk");
    // hold-batch + 2 full drain batches (batch-size 2) + 1 partial final batch
    assertEquals(4, bulks.size(), () -> "bulk request count: " + bulks.size());
    assertTrue(bulks.get(0).body().contains("SELECT hold-a"));
    assertTrue(bulks.get(0).body().contains("SELECT hold-b"));
    assertTrue(bulks.get(1).body().contains("SELECT drain-0"));
    assertTrue(bulks.get(1).body().contains("SELECT drain-1"));
    assertTrue(bulks.get(2).body().contains("SELECT drain-2"));
    assertTrue(bulks.get(2).body().contains("SELECT drain-3"));
    assertTrue(bulks.get(3).body().contains("SELECT drain-4"));
    for (FakeEsServer.RecordedRequest bulk : bulks) {
      assertTrue(occurrences(bulk.body(), "\"eventType\"") <= 2);
    }
    assertEquals(7.0, registry.get("sqlmask.audit.es.documents").counter().count());
    assertEquals(0, registry.find("sqlmask.audit.dropped").counters().size(),
        "no SHUTDOWN/ES_FAILURE/QUEUE_FULL drops on the drain path");
    assertEquals(0, r.droppedBatches());
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int i = 0; (i = haystack.indexOf(needle, i)) != -1; i += needle.length()) {
      count++;
    }
    return count;
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
