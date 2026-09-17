package io.sqlmask.audit;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Rate-limited failure reporting: at most one WARN per 60s window carrying the
 * counters accumulated since the last recovery, and a single INFO on the first
 * successful write after at least one failure (spec §4.2). All counters reset
 * on recovery, which also re-arms the WARN so the next failure warns again
 * immediately. Queue-full drops feed the same window as bulk failures — a drop
 * is a failure signal too — so the WARN carries 丢弃数 (queue-drops) /
 * 失败批数 (dropped-batches) / 当前队列深度 (queue).
 */
public final class FailureReporter {

  private static final long WINDOW_MS = 60_000;
  /** Queue-depth sentinel for callers that cannot know the depth. */
  static final int DEPTH_UNKNOWN = -1;

  private final Consumer<String> warn;
  private final Consumer<String> info;
  private Clock clock;

  /** Start of the current WARN window: a warning was emitted at this instant. */
  private long windowStart;
  private long droppedBatchesTotal;
  private long droppedEventsTotal;
  private long queueDroppedTotal;
  private boolean down;
  private long downSince;

  public FailureReporter(Clock clock, Consumer<String> warn, Consumer<String> info) {
    this.clock = clock;
    this.warn = warn;
    this.info = info;
    // Armed: the very first failure warns immediately.
    this.windowStart = clock.millis() - WINDOW_MS;
  }

  /** Test hook: move the reporter forward in time. */
  void advance(Clock newClock) {
    this.clock = newClock;
  }

  public synchronized void recordBatchFailure(int events) {
    recordBatchFailure(events, DEPTH_UNKNOWN);
  }

  /**
   * A bulk write failed for a batch of {@code events} events;
   * {@code queueDepth} is the recorder's current queue occupancy.
   */
  public synchronized void recordBatchFailure(int events, int queueDepth) {
    droppedBatchesTotal++;
    droppedEventsTotal += events;
    failure(queueDepth);
  }

  /** {@code count} events were discarded because the queue was full. */
  public synchronized void recordQueueDrop(int count) {
    recordQueueDrop(count, DEPTH_UNKNOWN);
  }

  /**
   * {@code count} events were discarded because the queue was full;
   * {@code queueDepth} is the recorder's current queue occupancy. Marks the
   * pipeline down like a bulk failure so the recovery INFO also fires.
   */
  public synchronized void recordQueueDrop(int count, int queueDepth) {
    queueDroppedTotal += count;
    droppedEventsTotal += count;
    failure(queueDepth);
  }

  /** Shared failure path: mark down, then warn at most once per 60s window. */
  private void failure(int queueDepth) {
    long now = clock.millis();
    if (!down) {
      down = true;
      downSince = now;
    }
    if (now - windowStart >= WINDOW_MS) {
      windowStart = now;
      warn.accept(String.format(
          "audit: ES write failing (dropped-batches=%d, events=%d, queue-drops=%d, queue=%s, "
              + "total-dropped-batches=%d, down-since-ms=%d) - events are being discarded "
              + "(best-effort)",
          droppedBatchesTotal, droppedEventsTotal, queueDroppedTotal, depth(queueDepth),
          droppedBatchesTotal, now - downSince));
    }
  }

  private static String depth(int queueDepth) {
    return queueDepth < 0 ? "n/a" : Integer.toString(queueDepth);
  }

  public synchronized void recordSuccess() {
    if (down) {
      info.accept(String.format(
          "audit: ES write recovered after %dms (dropped %d batches, %d queue-dropped "
              + "events during outage)",
          clock.millis() - downSince, droppedBatchesTotal, queueDroppedTotal));
      down = false;
      droppedBatchesTotal = 0;
      droppedEventsTotal = 0;
      queueDroppedTotal = 0;
      windowStart = clock.millis() - WINDOW_MS; // re-arm: a fresh failure warns again
    }
  }

  public synchronized long snapshotDroppedBatches() {
    return droppedBatchesTotal;
  }

  public synchronized long snapshotQueueDrops() {
    return queueDroppedTotal;
  }
}
