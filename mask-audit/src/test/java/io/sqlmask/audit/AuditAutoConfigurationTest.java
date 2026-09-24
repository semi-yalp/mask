package io.sqlmask.audit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withBean(SimpleMeterRegistry.class)
      .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

  @Test
  void disabledByDefaultFallsBackToNoopWithoutEsClient() {
    runner.run(ctx -> {
      assertThat(ctx).hasSingleBean(AuditRecorder.class);
      assertThat(ctx.getBean(AuditRecorder.class)).isInstanceOf(NoopAuditRecorder.class);
      assertThat(ctx).doesNotHaveBean(EsAuditRecorder.class);
    });
  }

  @Test
  void explicitEnabledBuildsEsRecorder() {
    runner.withPropertyValues("audit.enabled=true").run(ctx -> {
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

  @Test
  void esRequestTimeoutsAreConnect3sSocket10s() {
    org.apache.http.client.config.RequestConfig config =
        AuditAutoConfiguration.esTimeouts(
            org.apache.http.client.config.RequestConfig.custom()).build();
    assertThat(config.getConnectTimeout()).isEqualTo(3_000);
    assertThat(config.getSocketTimeout()).isEqualTo(10_000);
  }
}
