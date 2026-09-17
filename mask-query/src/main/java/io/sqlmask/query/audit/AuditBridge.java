package io.sqlmask.query.audit;

import io.sqlmask.audit.AuditRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/** Bridges mask-audit's recorder when the ES pipeline is deployed; without it
 * the auditor is a no-op and queries stay fully functional. */
@Configuration
public class AuditBridge {

  @Bean
  public Consumer<io.sqlmask.audit.AuditEvent> queryAuditSink(
      ObjectProvider<AuditRecorder> recorder) {
    AuditRecorder available = recorder.getIfAvailable();
    return available == null ? event -> {} : available::record;
  }
}
