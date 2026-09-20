package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureReporterTest {

  private static Clock at(String iso) {
    return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
  }

  @Test
  void firstFailureWarnsWithCountsThenSilentForWindow() {
    List<String> warns = new ArrayList<>();
    List<String> infos = new ArrayList<>();
    Clock clock = at("2026-09-16T00:00:00Z");
    FailureReporter r = new FailureReporter(clock, warns::add, infos::add);

    r.recordBatchFailure(200);
    r.recordBatchFailure(200);
    assertEquals(1, warns.size());
    assertTrue(warns.get(0).contains("dropped-batches=1"));
    assertTrue(warns.get(0).contains("events=200"));

    r.recordBatchFailure(200); // within 60s window: silent
    assertEquals(1, warns.size());
  }

  @Test
  void newWindowWarnsAgainWithAccumulatedCounts() {
    List<String> warns = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), warns::add, s -> {});
    r.recordBatchFailure(100);
    Clock later = Clock.fixed(Instant.parse("2026-09-16T00:01:01Z"), ZoneOffset.UTC);
    r.advance(later);
    r.recordBatchFailure(50);
    assertEquals(2, warns.size());
    assertTrue(warns.get(1).contains("total-dropped-batches=2"));
  }

  @Test
  void successAfterFailuresLogsRecoveryInfoOnce() {
    List<String> warns = new ArrayList<>();
    List<String> infos = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), warns::add, infos::add);
    r.recordBatchFailure(100);
    r.recordSuccess();
    r.recordSuccess();
    assertEquals(1, infos.size());
    assertTrue(infos.get(0).contains("recovered"));
    assertEquals(0, r.snapshotDroppedBatches()); // counters reset on recovery
    r.recordBatchFailure(10);
    assertEquals(2, warns.size()); // a fresh warn cycle after recovery
  }

  @Test
  void successWithoutFailureIsSilent() {
    List<String> infos = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), s -> {}, infos::add);
    r.recordSuccess();
    assertEquals(0, infos.size());
  }

  @Test
  void downSinceAccumulatesAcrossWindowsUntilRecovery() {
    // P2, §4.4-11: the second-window WARN's down-since-ms must keep counting
    // from the FIRST failure (accumulated outage), not restart per window.
    List<String> warns = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), warns::add, s -> {});
    r.recordBatchFailure(100); // first failure anchors down-since
    assertEquals(1, warns.size());
    assertTrue(warns.get(0).contains("down-since-ms=0"), () -> warns.get(0));
    // next 60s window: still down, so the WARN reports the accumulated
    // 61s outage since the first failure
    r.advance(Clock.fixed(Instant.parse("2026-09-16T00:01:01Z"), ZoneOffset.UTC));
    r.recordBatchFailure(50);
    assertEquals(2, warns.size());
    assertTrue(warns.get(1).contains("down-since-ms=61000"), () -> warns.get(1));
    // recovery resets the anchor: the next outage starts a fresh down-since
    r.recordSuccess();
    r.advance(Clock.fixed(Instant.parse("2026-09-16T00:01:02Z"), ZoneOffset.UTC));
    r.recordBatchFailure(1);
    assertEquals(3, warns.size());
    assertTrue(warns.get(2).contains("down-since-ms=0"), () -> warns.get(2));
    assertEquals(1, r.snapshotDroppedBatches()); // totals keep counting across cycles
  }
}
