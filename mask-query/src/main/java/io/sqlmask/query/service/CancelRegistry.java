package io.sqlmask.query.service;

import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks in-flight statements so the container-side async timeout
 * (WebAsyncTask onTimeout → cancel) can stop the running query instead of
 * letting it finish unobserved — Servlet async has no portable disconnect
 * callback, so cancellation rides the statement/container timeouts.
 *
 * <p>A cancel that arrives before the statement exists (the pipeline spends
 * time in metadata lookup, rewrite HTTP and connect before attaching) is
 * remembered and applied the moment a statement attaches, so the "cancelled
 * after the client already saw 503" query still stops at the earliest possible
 * point. cancel() on an unknown id is otherwise a no-op. */
@Component
public class CancelRegistry {

  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Statement> active = new ConcurrentHashMap<>();
  private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

  public record Registration(long id, CancelRegistry registry) {
    public void attach(Statement statement) {
      if (registry.cancelRequested.remove(id)) {
        try {
          statement.cancel();
        } catch (Exception ignored) {
          // the query is ending anyway; never let canceling mask the real outcome
        }
      }
      registry.active.put(id, statement);
    }
    public void detach() {
      registry.active.remove(id);
      registry.cancelRequested.remove(id);
    }
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
    } else {
      // not attached yet: remember so attach() cancels immediately
      cancelRequested.add(id);
    }
  }

  public void end(long id) {
    active.remove(id);
    cancelRequested.remove(id);
  }
}
