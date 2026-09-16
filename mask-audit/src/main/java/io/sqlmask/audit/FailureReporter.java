package io.sqlmask.audit;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Rate-limited failure reporting: at most one WARN per 60s window carrying the
 * counters accumulated since the last recovery, and a single INFO on the first
 * successful write after at least one failure (spec §4.2). All counters reset
 * on recovery, which also re-arms the WARN so the next failure warns again
 * immediately.
 */
public final class FailureReporter {

  private static final long WINDOW_MS = 60_000;

  private final Consumer<String> warn;
  private final Consumer<String> info;
  private Clock clock;

  /** Start of the current WARN window: a warning was emitted at this instant. */
  private long windowStart;
  private long droppedBatchesTotal;
  private long droppedEventsTotal;
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
    droppedBatchesTotal++;
    droppedEventsTotal += events;
    long now = clock.millis();
    if (!down) {
      down = true;
      downSince = now;
    }
    if (now - windowStart >= WINDOW_MS) {
      windowStart = now;
      warn.accept(String.format(
          "audit: ES write failing (dropped-batches=%d, events=%d, total-dropped-batches=%d, "
              + "down-since-ms=%d) - events are being discarded (best-effort)",
          droppedBatchesTotal, droppedEventsTotal, droppedBatchesTotal, now - downSince));
    }
  }

  public synchronized void recordSuccess() {
    if (down) {
      info.accept(String.format(
          "audit: ES write recovered after %dms (dropped %d batches during outage)",
          clock.millis() - downSince, droppedBatchesTotal));
      down = false;
      droppedBatchesTotal = 0;
      droppedEventsTotal = 0;
      windowStart = clock.millis() - WINDOW_MS; // re-arm: a fresh failure warns again
    }
  }

  public synchronized long snapshotDroppedBatches() {
    return droppedBatchesTotal;
  }
}
