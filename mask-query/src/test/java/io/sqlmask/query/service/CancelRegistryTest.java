package io.sqlmask.query.service;

import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CancelRegistryTest {

  /** Proxy-based Statement: only cancel() has observable behavior; hashCode/
   * equals/toString are identity-based because the registry keeps statements
   * in a map. */
  private static Statement countingStatement(AtomicInteger cancellations) {
    return (Statement) java.lang.reflect.Proxy.newProxyInstance(
        Statement.class.getClassLoader(),
        new Class<?>[] {Statement.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "cancel" -> {
            cancellations.incrementAndGet();
            yield null;
          }
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "CancellingStatement";
          default -> null;
        });
  }

  @Test
  void cancelBeforeAttachIsAppliedAtAttach() {
    // 取消请求发生在 attach 之前（阶段超时先于 attach 到来）：attach 时立即生效，
    // 查询不会继续在库上空跑
    CancelRegistry registry = new CancelRegistry();
    CancelRegistry.Registration registration = registry.begin();
    registry.cancel(registration.id());

    AtomicInteger cancellations = new AtomicInteger();
    Statement statement = countingStatement(cancellations);
    registration.attach(statement);
    assertThat(cancellations.get()).isEqualTo(1);
    registration.detach();
  }

  @Test
  void cancelAfterAttachStopsTheStatementDirectly() {
    CancelRegistry registry = new CancelRegistry();
    CancelRegistry.Registration registration = registry.begin();
    AtomicInteger cancellations = new AtomicInteger();
    Statement statement = countingStatement(cancellations);
    registration.attach(statement);

    registry.cancel(registration.id());
    assertThat(cancellations.get()).isEqualTo(1);

    registry.end(registration.id());
    // end 之后再 cancel：无目标、无副作用、也不残留待取消标记
    assertThatCode(() -> registry.cancel(registration.id())).doesNotThrowAnyException();
    assertThat(cancellations.get()).isEqualTo(1);
  }

  @Test
  void detachClearsAPendingCancelSoALaterAttachIsUntouched() {
    CancelRegistry registry = new CancelRegistry();
    CancelRegistry.Registration registration = registry.begin();
    registry.cancel(registration.id());
    registration.detach();

    AtomicInteger cancellations = new AtomicInteger();
    registration.attach(countingStatement(cancellations));
    assertThat(cancellations.get()).isEqualTo(0);
  }
}
