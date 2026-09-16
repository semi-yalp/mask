package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPropertiesTest {

  @Test
  void defaultsMatchSpec() {
    AuditProperties p = new AuditProperties();
    assertTrue(p.isEnabled());
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
