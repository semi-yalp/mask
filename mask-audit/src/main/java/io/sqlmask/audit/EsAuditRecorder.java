package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>Pipeline observability follows the prometheus-metrics spec §3.5 contract:
 * {@code sqlmask.audit.enqueued} (per event type), {@code sqlmask.audit.dropped}
 * (QUEUE_FULL / ES_FAILURE / SHUTDOWN), {@code sqlmask.audit.queue.depth} and
 * {@code .capacity} gauges, {@code sqlmask.audit.es.batches} (per outcome),
 * {@code sqlmask.audit.es.documents}, and the {@code sqlmask.audit.es.write}
 * timer around the bulk call.</p>
 */
public final class EsAuditRecorder implements AuditRecorder, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(EsAuditRecorder.class);
  private static final DateTimeFormatter INDEX_DAY =
      DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);
  private static final long CLOSE_TIMEOUT_MS = 5_000;
  private static final long POLL_MS = 200;
  private static final String METRIC_ENQUEUED = "sqlmask.audit.enqueued";
  private static final String METRIC_DROPPED = "sqlmask.audit.dropped";
  private static final String METRIC_BATCHES = "sqlmask.audit.es.batches";
  private static final String METRIC_DOCUMENTS = "sqlmask.audit.es.documents";

  /** SLO bucket bounds in seconds, spec §3.5 (5ms–10s). */
  private static final double[] WRITE_SLO_SECONDS = {
      0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500, 1.0, 2.5, 5.0, 10.0};

  private final ElasticsearchClient client;
  private final AuditProperties properties;
  private final MeterRegistry registry;
  private final ArrayBlockingQueue<AuditEvent> queue;
  private final Thread worker;
  private final FailureReporter reporter = new FailureReporter(
      Clock.systemUTC(), msg -> log.warn(msg), msg -> log.info(msg));
  private final IndexTemplateManager templates;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong failedBatches = new AtomicLong();
  private final Timer writeTimer;
  private volatile boolean running = true;

  public EsAuditRecorder(ElasticsearchClient client, AuditProperties properties,
      MeterRegistry registry) {
    this.client = client;
    this.properties = properties;
    this.registry = registry;
    this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
    this.templates = new IndexTemplateManager(client, properties.getIndexPrefix(), properties.getIndexReplicas());
    this.worker = new Thread(this::loop, "audit-es-writer");
    this.worker.setDaemon(true);
    // Micrometer gauges hold only a WeakReference to the observed object by
    // default: if this recorder instance were replaced, the old queue could
    // become unreachable and the gauges would report NaN. strongReference(true)
    // makes the registry keep an explicit strong reference to the queue.
    Gauge.builder("sqlmask.audit.queue.depth", queue, q -> q.size())
        .strongReference(true)
        .register(registry);
    int capacity = properties.getQueueCapacity();
    Gauge.builder("sqlmask.audit.queue.capacity", queue, q -> (double) capacity)
        .strongReference(true)
        .register(registry);
    this.writeTimer = Timer.builder("sqlmask.audit.es.write")
        .serviceLevelObjectives(sloDurations())
        .register(registry);
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
        registry.counter(METRIC_DROPPED, "reason", "QUEUE_FULL").increment();
      } else {
        registry.counter(METRIC_ENQUEUED, "event_type", event.eventType()).increment();
      }
    } catch (RuntimeException e) {
      // absolute guarantee: auditing never breaks the business request
    }
  }

  public long dropped() {
    return dropped.get();
  }

  public long droppedBatches() {
    return failedBatches.get();
  }

  private void loop() {
    List<AuditEvent> batch = new ArrayList<>();
    long lastFlush = System.currentTimeMillis();
    while (running || !queue.isEmpty() || !batch.isEmpty()) {
      try {
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
        // Clear the flag so the drain phase can keep polling; the interrupt
        // already did its job of breaking out of the blocking poll above.
        Thread.interrupted();
      } catch (RuntimeException e) {
        log.debug("audit: writer loop iteration failed: {}", e.getMessage());
      }
    }
  }

  private void flush(List<AuditEvent> batch) {
    if (batch.isEmpty()) {
      return;
    }
    long startNanos = System.nanoTime();
    try {
      var response = client.bulk(b -> {
        for (AuditEvent event : batch) {
          String index = indexName(properties.getIndexPrefix(), event.timestamp());
          b.operations(op -> op.index(idx -> idx.index(index)
              .document(AuditEventJson.toDocument(event, properties.getSqlMaxChars()))));
        }
        return b;
      });
      writeTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      if (response.errors()) {
        recordBatchFailure(batch.size());
      } else {
        registry.counter(METRIC_BATCHES, "outcome", "SUCCESS").increment();
        registry.counter(METRIC_DOCUMENTS).increment(batch.size());
        reporter.recordSuccess();
      }
    } catch (IOException | RuntimeException e) {
      writeTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
      recordBatchFailure(batch.size());
    } finally {
      batch.clear();
    }
  }

  /** A failed batch is one FAILED batch and, per spec §3.5, batch-size dropped events. */
  private void recordBatchFailure(int batchSize) {
    failedBatches.incrementAndGet();
    registry.counter(METRIC_BATCHES, "outcome", "FAILURE").increment();
    registry.counter(METRIC_DROPPED, "reason", "ES_FAILURE").increment(batchSize);
    reporter.recordBatchFailure(batchSize);
  }

  static String indexName(String prefix, Instant at) {
    return prefix + "-" + INDEX_DAY.format(at);
  }

  @Override
  public void close() {
    running = false;
    worker.interrupt();
    try {
      worker.join(CLOSE_TIMEOUT_MS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) {
      long pending = queue.size();
      log.warn("audit: writer did not drain in {}ms, {} queued events discarded",
          CLOSE_TIMEOUT_MS, pending);
      registry.counter(METRIC_DROPPED, "reason", "SHUTDOWN").increment(pending);
    }
  }

  /**
   * Micrometer 1.13 的 Timer SLO 只有 {@code Duration} 重载（无 double 按秒重载），
   * 故把 {@link #WRITE_SLO_SECONDS} 的秒值换算为 Duration（5ms–10s 均为精确纳秒值）。
   */
  private static Duration[] sloDurations() {
    return Arrays.stream(WRITE_SLO_SECONDS)
        .mapToObj(seconds -> Duration.ofNanos((long) (seconds * 1_000_000_000L)))
        .toArray(Duration[]::new);
  }
}
