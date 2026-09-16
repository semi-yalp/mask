package io.sqlmask.audit;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Rate-limited failure reporting: at most one WARN per 60s window carrying the
 * window's accumulated counters, and a single INFO on the first successful
 * write after at least one failure (spec §4.2). All counters reset on recovery.
 */
public final class FailureReporter {

  private static final long WINDOW_MS = 60_000;

  private final Consumer<String> warn;
  private final Consumer<String> info;
  private Clock clock;

  /** Half of MIN_VALUE: any real clock reads far past the window, so the very
   * first failure (and the first after each recovery) warns immediately. */
  private static final long FRESH_WINDOW = Long.MIN_VALUE / 2;

  private long windowStart = FRESH_WINDOW;
  private long windowDroppedBatches;
  private long windowDroppedEvents;
  private long droppedBatchesTotal;
  private boolean down;
  private long downSince;

  public FailureReporter(Clock clock, Consumer<String> warn, Consumer<String> info) {
    this.clock = clock;
    this.warn = warn;
    this.info = info;
  }

  /** Test hook: move the reporter forward in time. */
  void advance(Clock newClock) {
    this.clock = newClock;
  }

  public synchronized void recordBatchFailure(int events) {
    long now = clock.millis();
    droppedBatchesTotal++;
    boolean windowOpened = now - windowStart >= WINDOW_MS;
    if (windowOpened) {
      windowStart = now;
      windowDroppedBatches = 0;
      windowDroppedEvents = 0;
    }
    windowDroppedBatches++;
    windowDroppedEvents += events;
    if (!down) {
      down = true;
      downSince = now;
    }
    if (windowOpened) {
      warn.accept(String.format(
          "audit: ES write failing (dropped-batches=%d, events=%d, total-dropped-batches=%d, "
              + "down-since-ms=%d) - events are being discarded (best-effort)",
          windowDroppedBatches, windowDroppedEvents, droppedBatchesTotal, now - downSince));
    }
  }

  public synchronized void recordSuccess() {
    if (down) {
      info.accept(String.format(
          "audit: ES write recovered after %dms (dropped %d batches during outage)",
          clock.millis() - downSince, droppedBatchesTotal));
      down = false;
      droppedBatchesTotal = 0;
      windowDroppedBatches = 0;
      windowDroppedEvents = 0;
      windowStart = FRESH_WINDOW;
    }
  }

  public synchronized long snapshotDroppedBatches() {
    return droppedBatchesTotal;
  }
}
