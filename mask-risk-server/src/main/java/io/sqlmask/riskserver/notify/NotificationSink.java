package io.sqlmask.riskserver.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.riskserver.config.RiskProperties;
import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alert notification sink: every qualifying new alert produces one
 * {@link Notification} in a bounded local log (the console's 通知记录), and,
 * when a webhook URL is configured, one async best-effort POST carrying the
 * alert payload. Delivery never blocks or fails the ingest path.
 */
public class NotificationSink {

  private static final Logger log = LoggerFactory.getLogger(NotificationSink.class);
  private static final int LOG_CAP = 300;

  private final RiskProperties.Notify config;
  private final RiskSeverity minSeverity;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2))
      .build();
  private final ObjectMapper mapper = new ObjectMapper();
  private final Deque<Notification> logEntries = new ArrayDeque<>();
  private final AtomicLong seq = new AtomicLong();

  public NotificationSink(RiskProperties.Notify config) {
    this.config = config;
    this.minSeverity = RiskSeverity.parse(config.getMinSeverity());
  }

  /** True when this alert's severity qualifies for notification. */
  public boolean qualifies(Alert alert) {
    return alert.severity().atLeast(minSeverity);
  }

  /** Records (and optionally delivers) one notification for a new alert. */
  public void notify(Alert alert) {
    if (!qualifies(alert)) {
      return;
    }
    Map<String, Object> payload = payload(alert);
    String delivery;
    String detail;
    String url = config.getWebhookUrl();
    if (url == null || url.isBlank()) {
      delivery = "RECORDED";
      detail = "未配置 webhook(risk.notify.webhook-url),仅本地记录";
    } else {
      try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
          delivery = "SENT";
          detail = "webhook 响应 HTTP " + response.statusCode();
        } else {
          delivery = "FAILED";
          detail = "webhook 响应 HTTP " + response.statusCode();
        }
      } catch (IOException | InterruptedException | RuntimeException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        delivery = "FAILED";
        detail = "webhook 投递失败: " + e.getClass().getSimpleName();
      }
    }
    Notification entry = new Notification("ntf-" + seq.incrementAndGet(),
        System.currentTimeMillis(), alert.id(), alert.ruleId(), alert.severity().wire(),
        alert.user(), alert.title(), delivery, detail);
    synchronized (logEntries) {
      logEntries.addFirst(entry);
      while (logEntries.size() > LOG_CAP) {
        logEntries.removeLast();
      }
    }
    log.info("risk: notification {} [{}] for alert {} ({})", entry.id(), delivery,
        alert.id(), alert.title());
  }

  /** Webhook JSON payload (also the shape shown in the console panel). */
  public Map<String, Object> payload(Alert alert) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source", "mask-risk-server");
    payload.put("alertId", alert.id());
    payload.put("ruleId", alert.ruleId());
    payload.put("ruleName", alert.ruleName());
    payload.put("category", alert.category());
    payload.put("severity", alert.severity().wire());
    payload.put("user", alert.user());
    payload.put("sourceIp", alert.sourceIp());
    payload.put("title", alert.title());
    payload.put("description", alert.description());
    payload.put("eventCount", alert.eventCount());
    payload.put("sqlSnippet", alert.sqlSnippet());
    payload.put("createdAt", alert.createdAt());
    payload.put("lastHitAt", alert.lastHitAt());
    return payload;
  }

  /** Newest-first snapshot for the console. */
  public List<Notification> recent(int limit) {
    synchronized (logEntries) {
      return new ArrayList<>(logEntries).subList(0, Math.min(limit, logEntries.size()));
    }
  }

  public boolean webhookConfigured() {
    return config.getWebhookUrl() != null && !config.getWebhookUrl().isBlank();
  }
}
