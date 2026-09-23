package io.sqlmask.query.audit;

import io.sqlmask.audit.AuditRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/** Bridges mask-audit's recorder when the ES pipeline is deployed; without it
 * the auditor is a no-op and queries stay fully functional — with one loud
 * signal at startup, so a deployment that expected audit trails does not
 * discover the gap from their absence. */
@Configuration
public class AuditBridge {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(AuditBridge.class);

  @Bean
  public Consumer<io.sqlmask.audit.AuditEvent> queryAuditSink(
      ObjectProvider<AuditRecorder> recorder) {
    AuditRecorder available = recorder.getIfAvailable();
    if (available == null) {
      LOG.warn("no AuditRecorder bean (audit.enabled=false or no ES pipeline): QUERY audit "
          + "events are NOT recorded; enable it with AUDIT_ENABLED=true");
      return event -> {};
    }
    return available::record;
  }
}
