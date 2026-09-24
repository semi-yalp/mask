package io.sqlmask.riskserver;

import io.sqlmask.riskserver.config.RiskApiFilter;
import io.sqlmask.riskserver.config.RiskProperties;
import io.sqlmask.riskserver.demo.AttackScenarios;
import io.sqlmask.riskserver.demo.DemoSeeder;
import io.sqlmask.riskserver.engine.AlertManager;
import io.sqlmask.riskserver.engine.BuiltInRules;
import io.sqlmask.riskserver.engine.RiskEngine;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import io.sqlmask.riskserver.stats.StatsCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import static org.springframework.core.Ordered.HIGHEST_PRECEDENCE;

/**
 * mask-risk-server (port 8084): the risk monitoring &amp; alerting service.
 * Ingests audit events (pushed by mask-audit's forwarder or the demo
 * endpoints), runs built-in + custom detection rules, scores events, groups
 * alerts and serves the risk console API.
 */
@SpringBootApplication
@EnableConfigurationProperties(RiskProperties.class)
public class RiskServerApplication {

  private static final Logger log = LoggerFactory.getLogger(RiskServerApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(RiskServerApplication.class, args);
  }

  @Bean
  MemoryRiskStore riskStore(RiskProperties properties) {
    String path = properties.getStore().getPersistencePath();
    if (path == null || path.isBlank()) {
      return new MemoryRiskStore(properties.getStore().getMaxEvents());
    }
    // Spring infers close() as the destroy method - final flush on shutdown.
    return io.sqlmask.riskserver.store.PersistingRiskStore.loadOrCreate(
        java.nio.file.Path.of(path),
        properties.getStore().getMaxEvents(),
        properties.getStore().getMaxPersistedEvents());
  }

  @Bean
  io.sqlmask.riskserver.notify.NotificationSink notificationSink(RiskProperties properties) {
    return new io.sqlmask.riskserver.notify.NotificationSink(properties.getNotify());
  }

  @Bean
  AlertManager alertManager(MemoryRiskStore store, RiskProperties properties,
      io.sqlmask.riskserver.notify.NotificationSink notifications) {
    return new AlertManager(store,
        RiskSeverity.parse(properties.getAlerting().getMinSeverity()),
        properties.getAlerting().getCooldownMinutes() * 60_000L,
        notifications);
  }

  @Bean
  io.sqlmask.riskserver.block.BlockService blockService(RiskProperties properties) {
    return new io.sqlmask.riskserver.block.BlockService(properties.getBlock());
  }

  @Bean
  io.sqlmask.riskserver.engine.BaselineService baselineService(
      MemoryRiskStore store, RiskProperties properties) {
    return new io.sqlmask.riskserver.engine.BaselineService(
        store, properties.getUeba().getRefreshSeconds() * 1000L);
  }

  @Bean
  RiskEngine riskEngine(MemoryRiskStore store, AlertManager alertManager,
      io.sqlmask.riskserver.engine.BaselineService baselineService) {
    return new RiskEngine(store, alertManager, baselineService);
  }

  @Bean
  DemoSeeder demoSeeder(RiskEngine engine) {
    return new DemoSeeder(engine);
  }

  @Bean
  AttackScenarios attackScenarios(RiskEngine engine) {
    return new AttackScenarios(engine);
  }

  @Bean
  StatsCalculator statsCalculator(MemoryRiskStore store) {
    return new StatsCalculator(store);
  }

  @Bean
  FilterRegistrationBean<RiskApiFilter> riskApiFilter(RiskProperties properties) {
    FilterRegistrationBean<RiskApiFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new RiskApiFilter(properties.getApiKey()));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  /** Boot: merge built-in upgrades into (possibly restored) state + optional demo seed. */
  @Bean
  ApplicationRunner riskBootstrap(RiskEngine engine, DemoSeeder seeder,
      RiskProperties properties) {
    return args -> {
      boolean restored = !engine.store().snapshotRules().isEmpty();
      // merge (not replace): adds built-ins introduced by upgrades, preserves
      // user edits to existing ones; on a fresh store this loads everything.
      for (RiskRule rule : BuiltInRules.catalog()) {
        if (engine.store().findRule(rule.id()).isEmpty()) {
          engine.store().putRule(rule);
          if (restored) {
            log.info("risk: upgrade added built-in rule {} to restored store", rule.id());
          }
        }
      }
      if (engine.store().snapshotSensitiveColumns().isEmpty()) {
        for (SensitiveColumn column : BuiltInRules.defaultSensitiveColumns()) {
          engine.store().putSensitiveColumn(column);
        }
        log.info("risk: seeded {} sensitive assets", BuiltInRules.defaultSensitiveColumns().size());
      }
      if (properties.getDemo().isSeedOnStart() && !restored) {
        seeder.seed();
      } else if (restored) {
        log.info("risk: restored state present - demo auto-seed skipped "
            + "(POST /api/risk/demo/seed to rebuild)");
      }
    };
  }
}
