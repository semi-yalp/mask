package io.sqlmask.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

  @Test
  void enabledByDefaultCreatesEsRecorder() {
    runner.run(ctx -> {
      assertThat(ctx).hasSingleBean(AuditRecorder.class);
      assertThat(ctx).hasSingleBean(EsAuditRecorder.class);
      assertThat(ctx).hasSingleBean(AuditProperties.class);
    });
  }

  @Test
  void disabledFallsBackToNoopWithoutEsClient() {
    runner.withPropertyValues("audit.enabled=false").run(ctx -> {
      assertThat(ctx).hasSingleBean(AuditRecorder.class);
      assertThat(ctx.getBean(AuditRecorder.class)).isInstanceOf(NoopAuditRecorder.class);
      assertThat(ctx).doesNotHaveBean(EsAuditRecorder.class);
    });
  }

  @Test
  void noopRecordSurvivesNullAndValue() {
    runner.withPropertyValues("audit.enabled=false").run(ctx -> {
      AuditRecorder r = ctx.getBean(AuditRecorder.class);
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        r.record(null);
        r.record(AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 1L, null, null,
            null, null, null, null, null, null, null, null, null, null));
      });
    });
  }

  /** Final-review I1 / spec §4.1: bulk HTTP timeouts connect 3s / socket 10s. */
  @Test
  void esRequestTimeoutsAreConnect3sSocket10s() {
    org.apache.http.client.config.RequestConfig config =
        AuditAutoConfiguration.esTimeouts(
            org.apache.http.client.config.RequestConfig.custom()).build();
    org.junit.jupiter.api.Assertions.assertEquals(3000, config.getConnectTimeout());
    org.junit.jupiter.api.Assertions.assertEquals(10000, config.getSocketTimeout());
  }
}
