package io.sqlmask.policyserver.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.common.effective.EffectiveConfigResponse;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data-plane metrics of {@link EffectiveConfigController} (gap §4.6 #2):
 * {@code sqlmask.effective.*} — pull counter, config_version/policies gauges and
 * the compile timer — over the three outcomes. {@code PolicyService} is mocked so
 * every branch is driveable deterministically (the real service flow through this
 * same context is covered by {@link EffectiveConfigEndpointTest}); the controller
 * and {@code EffectiveMetrics} wiring stay real, so the assertions exercise the
 * production recording path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EffectiveConfigMetricsTest {

  private static final String NOT_FOUND = "(not_found)";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @MockBean
  private PolicyService service;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void successfulPullRecordsCounterGaugesAndCompileTimer() throws Exception {
    double beforePull = pullCounter("pg_prod", "postgresql", "SUCCESS");

    EffectiveConfigResponse response = new EffectiveConfigResponse("pg_prod", "postgresql", 3L,
        new EffectiveConfigResponse.PolicySummary(2, 1),
        new EffectiveConfigResponse.ConfigPayload(
            new EffectiveConfigResponse.MetadataPayload(List.of()), List.of(), Map.of()));
    when(service.effective(anyString(), any(Subject.class))).thenReturn(response);

    mvc.perform(get("/api/effective/pg_prod").param("user", "alice").param("groups", "a,b"))
        .andExpect(status().isOk());

    assertThat(pullCounter("pg_prod", "postgresql", "SUCCESS")).isEqualTo(beforePull + 1);
    assertThat(registry.get("sqlmask.effective.config_version").tag("instance", "pg_prod")
        .gauge().value()).isEqualTo(3.0);
    assertThat(registry.get("sqlmask.effective.policies").tag("instance", "pg_prod")
        .gauge().value()).isEqualTo(2.0);
    assertThat(registry.get("sqlmask.effective.compile").tag("instance", "pg_prod")
        .timer().count()).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void unknownInstanceIncrementsNotFoundSeries() throws Exception {
    double before = pullCounter(NOT_FOUND, NOT_FOUND, "FAILURE");
    long beforeTimer = compileTimerCount(NOT_FOUND);

    when(service.effective(anyString(), any(Subject.class))).thenThrow(
        new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
            "instance 'nope' not found"));

    mvc.perform(get("/api/effective/nope").param("user", "alice"))
        .andExpect(status().isNotFound());

    assertThat(pullCounter(NOT_FOUND, NOT_FOUND, "FAILURE")).isEqualTo(before + 1);
    assertThat(compileTimerCount(NOT_FOUND)).isEqualTo(beforeTimer + 1);
  }

  @Test
  void otherRuntimeFailureIncrementsFailureSeries() throws Exception {
    double before = pullCounter("pg_prod", "(unknown)", "FAILURE");
    long beforeTimer = compileTimerCount("pg_prod");

    when(service.effective(anyString(), any(Subject.class)))
        .thenThrow(new IllegalStateException("boom"));

    mvc.perform(get("/api/effective/pg_prod")).andExpect(status().isInternalServerError());

    assertThat(pullCounter("pg_prod", "(unknown)", "FAILURE")).isEqualTo(before + 1);
    assertThat(compileTimerCount("pg_prod")).isEqualTo(beforeTimer + 1);
  }

  private double pullCounter(String instance, String dialect, String outcome) {
    return registry.counter("sqlmask.effective.pull",
        "instance", instance, "dialect", dialect, "outcome", outcome).count();
  }

  private long compileTimerCount(String instance) {
    // create-or-get (same semantics as Registry.counter()): safe to read before
    // the meter exists yet, and it returns the SLO-registered timer once recorded.
    return registry.timer("sqlmask.effective.compile", "instance", instance).count();
  }
}