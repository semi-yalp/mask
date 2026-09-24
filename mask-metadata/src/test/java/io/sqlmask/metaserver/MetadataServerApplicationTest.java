package io.sqlmask.metaserver;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Context smoke test for the metadata domain as a library. */
@SpringBootTest(properties = "spring.sql.init.mode=never")
class MetadataServerApplicationTest {

  @Autowired
  private ApplicationContext context;

  @Test
  void contextLoads() {
    assertNotNull(context.getBean(MetadataTestApp.class));
    assertNotNull(context.getBean(io.sqlmask.metaserver.service.MetadataService.class));
  }
}
