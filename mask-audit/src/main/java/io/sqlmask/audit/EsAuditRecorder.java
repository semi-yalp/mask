package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort async writer (spec §4): a bounded queue, one daemon worker that
 * flushes a batch when {@code batch-size} events accumulated or
 * {@code flush-interval-ms} elapsed, one bulk request per batch. Queue-full
 * drops and failed batches are counted, never retried, and never propagated —
 * {@link #record} cannot throw. Close drains the queue for at most 5s.
 */
public final class EsAuditRecorder implements AuditRecorder, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(EsAuditRecorder.class);
  private static final DateTimeFormatter INDEX_DAY =
      DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);
  private static final long CLOSE_TIMEOUT_MS = 5_000;
  private static final long POLL_MS = 200;

  private final ElasticsearchClient client;
  private final AuditProperties properties;
  private final ArrayBlockingQueue<AuditEvent> queue;
  private final Thread worker;
  private final FailureReporter reporter = new FailureReporter(
      Clock.systemUTC(), msg -> log.warn(msg), msg -> log.info(msg));
  private final IndexTemplateManager templates;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong failedBatches = new AtomicLong();
  private volatile boolean running = true;

  public EsAuditRecorder(ElasticsearchClient client, AuditProperties properties) {
    this.client = client;
    this.properties = properties;
    this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
    this.templates = new IndexTemplateManager(client, properties.getIndexPrefix());
    this.worker = new Thread(this::loop, "audit-es-writer");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  @Override
  public void record(AuditEvent event) {
    if (event == null) {
      return;
    }
    try {
      if (!queue.offer(event)) {
        dropped.incrementAndGet();
        // surface the drop through the same rate-limited WARN as bulk failures
        // (spec §4.2) — a queue-full drop is a failure signal, not silence.
        reporter.recordQueueDrop(1, queue.size());
      }
    } catch (RuntimeException e) {
      // absolute guarantee: auditing never breaks the business request
    }
  }

  /** Events discarded because the queue was full. */
  public long dropped() {
    return dropped.get();
  }

  /** Batches discarded because the bulk write failed. */
  public long droppedBatches() {
    return failedBatches.get();
  }

  private void loop() {
    List<AuditEvent> batch = new ArrayList<>();
    long lastFlush = System.currentTimeMillis();
    while (running || !queue.isEmpty() || !batch.isEmpty()) {
      try {
        // Self-rate-limited to one PUT per 60s until it succeeds.
        templates.ensureIfStale(System.currentTimeMillis());
        AuditEvent e = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
        if (e != null) {
          batch.add(e);
        }
        long now = System.currentTimeMillis();
        boolean sizeReached = batch.size() >= properties.getBatchSize();
        boolean timedOut = !batch.isEmpty() && now - lastFlush >= properties.getFlushIntervalMs();
        boolean draining = !running && queue.isEmpty() && !batch.isEmpty();
        if (sizeReached || timedOut || draining) {
          flush(batch);
          lastFlush = now;
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt(); // re-evaluate the loop condition
      } catch (RuntimeException e) {
        log.debug("audit: writer loop iteration failed: {}", e.getMessage());
      }
    }
  }

  private void flush(List<AuditEvent> batch) {
    if (batch.isEmpty()) {
      return;
    }
    try {
      var response = client.bulk(b -> {
        for (AuditEvent event : batch) {
          String index = indexName(properties.getIndexPrefix(), event.timestamp());
          b.operations(op -> op.index(idx -> idx.index(index)
              .document(AuditEventJson.toDocument(event, properties.getSqlMaxChars()))));
        }
        return b;
      });
      if (response.errors()) {
        failedBatches.incrementAndGet();
        reporter.recordBatchFailure(batch.size(), queue.size());
      } else {
        reporter.recordSuccess();
      }
    } catch (IOException | RuntimeException e) {
      failedBatches.incrementAndGet();
      reporter.recordBatchFailure(batch.size(), queue.size());
    } finally {
      batch.clear();
    }
  }

  static String indexName(String prefix, Instant at) {
    return prefix + "-" + INDEX_DAY.format(at);
  }

  /**
   * Stops accepting events and drains the queue for at most 5s. The worker is
   * not interrupted on purpose: with the interrupt flag set, a blocking
   * {@code queue.poll} throws immediately without dequeuing, which would
   * prevent the drain it is meant to speed up; the worker instead notices
   * {@code running == false} within one poll cycle and flushes what is left.
   */
  @Override
  public void close() {
    running = false;
    try {
      worker.join(CLOSE_TIMEOUT_MS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) {
      long pending = queue.size();
      log.warn("audit: writer did not drain in {}ms, {} queued events discarded",
          CLOSE_TIMEOUT_MS, pending);
    }
  }
}
