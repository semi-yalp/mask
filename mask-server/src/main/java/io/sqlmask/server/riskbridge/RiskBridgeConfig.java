package io.sqlmask.server.riskbridge;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEventJson;
import io.sqlmask.audit.AuditProperties;
import io.sqlmask.audit.RiskIngestSink;
import io.sqlmask.riskserver.engine.RiskEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * In-process audit → risk bridge: every audited event is offered to the
 * detection engine in the same JVM (no HTTP hop, no dropped queue). The
 * document shape is exactly what the standalone ingest endpoint accepts, so
 * both transports feed one parser. Risk monitoring therefore works with
 * {@code audit.enabled=false} too — persistence and detection are separate
 * concerns.
 */
@Configuration
public class RiskBridgeConfig {

  @Bean
  RiskIngestSink riskIngestSink(RiskEngine engine, AuditProperties properties) {
    return event -> {
      if (event == null) {
        return;
      }
      try {
        engine.ingest(List.of(AuditEventJson.toDocument(event, properties.getSqlMaxChars())));
      } catch (RuntimeException e) {
        // best-effort: risk detection must never break the auditing path
      }
    };
  }
}
