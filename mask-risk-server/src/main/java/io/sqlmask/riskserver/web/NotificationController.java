package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.notify.NotificationSink;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The console's notification log: what was sent (or locally recorded) and why. */
@RestController
@RequestMapping("/api/risk/notifications")
public class NotificationController {

  private final NotificationSink sink;

  public NotificationController(NotificationSink sink) {
    this.sink = sink;
  }

  @GetMapping
  public Map<String, Object> notifications(@RequestParam(defaultValue = "100") int limit) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("webhookConfigured", sink.webhookConfigured());
    out.put("total", sink.recent(Math.max(1, Math.min(300, limit))).size());
    out.put("notifications", sink.recent(Math.max(1, Math.min(300, limit))));
    return out;
  }
}
