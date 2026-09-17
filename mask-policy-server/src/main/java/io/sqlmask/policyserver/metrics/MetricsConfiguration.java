package io.sqlmask.policyserver.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.server.EffectiveMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link EffectiveMetrics} 是 mask-core 的 {@code @Component}（其自身上下文经
 * 组件扫描注册）；本服务的上下文只扫描 {@code io.sqlmask.policyserver}，故在此
 * 显式声明 bean 供 EffectiveConfigController 注入。两个上下文互不重叠，不会重复注册。
 */
@Configuration
public class MetricsConfiguration {

  @Bean
  public EffectiveMetrics effectiveMetrics(MeterRegistry registry) {
    return new EffectiveMetrics(registry);
  }
}
