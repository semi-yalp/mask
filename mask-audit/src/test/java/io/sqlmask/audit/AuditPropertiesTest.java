package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPropertiesTest {

  @Test
  void defaultsMatchSpec() {
    AuditProperties p = new AuditProperties();
    // 默认关闭:未显式 audit.enabled=true 时走 Noop 记录器,不指向 localhost ES
    assertEquals(false, p.isEnabled());
    assertTrue(p.isEffectivePullEnabled());
    assertEquals("mask-audit", p.getIndexPrefix());
    assertEquals(10000, p.getQueueCapacity());
    assertEquals(200, p.getBatchSize());
    assertEquals(2000L, p.getFlushIntervalMs());
    assertEquals(8192, p.getSqlMaxChars());
    assertEquals("http://127.0.0.1:9200", p.getElasticsearch().getUrl());
    assertEquals("", p.getElasticsearch().getApiKey());
    assertEquals("", p.getElasticsearch().getUsername());
    assertEquals("", p.getElasticsearch().getPassword());
  }
}
