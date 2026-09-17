package io.sqlmask.query.service;

import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks in-flight statements so the container-side async timeout
 * (WebAsyncTask onTimeout → cancel) can stop the running query instead of
 * letting it finish unobserved — Servlet async has no portable disconnect
 * callback, so cancellation rides the statement/container timeouts.
 * cancel() on an unknown id is a no-op. */
@Component
public class CancelRegistry {

  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Statement> active = new ConcurrentHashMap<>();

  public record Registration(long id, CancelRegistry registry) {
    public void attach(Statement statement) { registry.active.put(id, statement); }
    public void detach() { registry.active.remove(id); }
  }

  public Registration begin() {
    return new Registration(ids.incrementAndGet(), this);
  }

  public void cancel(long id) {
    Statement statement = active.get(id);
    if (statement != null) {
      try {
        statement.cancel();
      } catch (Exception ignored) {
        // the query is ending anyway; never let canceling mask the real outcome
      }
    }
  }

  public void end(long id) {
    active.remove(id);
  }
}
