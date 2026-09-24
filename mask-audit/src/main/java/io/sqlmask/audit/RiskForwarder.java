package io.sqlmask.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort async forwarder that ships every {@link AuditEvent} to the risk
 * monitoring service's ingest endpoint, mirroring {@link EsAuditRecorder}'s
 * semantics: a bounded queue, one daemon worker, batch flush by size or by a
 * max hold time after the first event, and never a throw (nor a block) on
 * the caller's thread. Failures are rate-limited-logged and counted; the
 * queue never retries.
 */
public final class RiskForwarder implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RiskForwarder.class);

  private final String url;
  private final String apiKey;
  private final int batchSize;
  private final long flushIntervalMs;
  private final int sqlMaxChars;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http;
  private final ArrayBlockingQueue<AuditEvent> queue;
  private final Thread worker;
  private final FailureReporter failures =
      new FailureReporter(Clock.systemUTC(), log::warn, log::info);
  private final AtomicLong dropped = new AtomicLong();

  private final Counter enqueued;
  private final Counter droppedCounter;
  private final Counter batches;

  private volatile boolean closing;

  public RiskForwarder(RiskForwardProperties properties, MeterRegistry registry) {
    this.url = properties.getUrl();
    this.apiKey = properties.getApiKey();
    this.batchSize = Math.max(1, properties.getBatchSize());
    this.flushIntervalMs = Math.max(50, properties.getFlushIntervalMs());
    this.sqlMaxChars = properties.getSqlMaxChars();
    this.queue = new ArrayBlockingQueue<>(Math.max(16, properties.getQueueCapacity()));
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    this.worker = new Thread(this::run, "risk-forwarder");
    this.worker.setDaemon(true);
    this.worker.start();
    if (registry != null) {
      this.enqueued = Counter.builder("sqlmask.risk.forward.enqueued").register(registry);
      this.droppedCounter = Counter.builder("sqlmask.risk.forward.dropped").register(registry);
      this.batches = Counter.builder("sqlmask.risk.forward.batches").register(registry);
    } else {
      this.enqueued = null;
      this.droppedCounter = null;
      this.batches = null;
    }
    log.info("audit: risk forwarding enabled -> {} (queue={}, batch={}, flushMs={})",
        url, properties.getQueueCapacity(), batchSize, flushIntervalMs);
  }

  /** Enqueues one event; drops (and counts) when the queue is full. */
  public void record(AuditEvent event) {
    if (event == null || closing) {
      return;
    }
    if (queue.offer(event)) {
      if (enqueued != null) {
        enqueued.increment();
      }
    } else {
      long total = dropped.incrementAndGet();
      if (droppedCounter != null) {
        droppedCounter.increment();
      }
      if (total <= 3 || total % 100 == 0) {
        log.warn("audit: risk forward queue full, event dropped (total dropped={})", total);
      }
    }
  }

  private void run() {
    // A batch ships when it fills to batchSize, or when flushIntervalMs has
    // elapsed since the first event — the interval is a max hold, not a
    // separate timer, so a sparse stream is batched by time and a busy one
    // by size, and the worker never flushes a half batch out from under a
    // concurrent record() just because a clock ticked.
    List<AuditEvent> batch = new ArrayList<>(batchSize);
    while (!closing) {
      try {
        AuditEvent first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
        if (first == null) {
          continue; // idle for a full interval: nothing to send
        }
        batch.add(first);
        queue.drainTo(batch, batchSize - 1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushIntervalMs);
        while (batch.size() < batchSize && !closing) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            break; // hold interval elapsed: ship what we have
          }
          AuditEvent more = queue.poll(remaining, TimeUnit.NANOSECONDS);
          if (more == null) {
            break; // no more traffic right now: ship what we have
          }
          batch.add(more);
        }
        flush(batch);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable t) {
        // Defensive: the worker must survive anything a single flush can throw.
        log.warn("audit: risk forward worker error: {}", t.toString());
      } finally {
        batch.clear();
      }
    }
  }

  private void flush(List<AuditEvent> batch) {
    if (batch.isEmpty()) {
      return;
    }
    try {
      List<Map<String, Object>> docs = new ArrayList<>(batch.size());
      for (AuditEvent event : batch) {
        docs.add(AuditEventJson.toDocument(event, sqlMaxChars));
      }
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(3))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(docs)));
      if (apiKey != null && !apiKey.isBlank()) {
        request.header("X-Api-Key", apiKey);
      }
      HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IOException("ingest responded HTTP " + response.statusCode());
      }
      if (batches != null) {
        batches.increment();
      }
      failures.recordSuccess();
    } catch (IOException | InterruptedException | RuntimeException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failures.recordBatchFailure(batch.size());
    }
  }

  /** Stops accepting events and drains up to ~3s of in-flight work. */
  @Override
  public void close() {
    closing = true;
    worker.interrupt();
    try {
      worker.join(3_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (!queue.isEmpty()) {
      log.info("audit: risk forwarder closed with {} events unsent (best-effort)", queue.size());
    }
  }
}
