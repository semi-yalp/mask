package io.sqlmask.metaserver.classification;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.StructureService;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
class ClassificationServiceTest {

  private static final String CUSTOMER = "crm.public.customer";

  @Autowired
  private ClassificationService classifications;
  @Autowired
  private MetadataService instances;
  @Autowired
  private StructureService structures;
  @Autowired
  private InMemoryMetaStore metaStore;
  @Autowired
  private InMemoryClassificationStore classificationStore;

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    InMemoryMetaStore inMemoryMetaStore() {
      return new InMemoryMetaStore();
    }

    @Bean
    @Primary
    InMemoryClassificationStore inMemoryClassificationStore() {
      return new InMemoryClassificationStore();
    }
  }

  @BeforeEach
  void seed() {
    metaStore.instances.clear();
    metaStore.structures.clear();
    metaStore.versionBumps.clear();
    classificationStore.clear();
    instances.create("pg_prod", "postgresql", null, null);
    instances.create("my_prod", "mysql", null, null);
    structures.replace("pg_prod", List.of(table("customer",
        "id", "phone_number", "email", "full_name", "home_city", "balance", "order_id")));
    structures.replace("my_prod", List.of(table("orders",
        "id", "amount", "buyer_name")));
  }

  private static TableStructure table(String name, String... columns) {
    return new TableStructure("crm", "public", name,
        java.util.Arrays.stream(columns)
            .map(column -> new TableStructure.ColumnStructure(column, "varchar")).toList());
  }

  @Test
  void autoClassifyInsertsOnlyAndNeverOverwrites() {
    // manual assertion on email must survive the auto pass untouched
    classifications.upsert("pg_prod", CUSTOMER + ".email", "OTHER", "LOW", "checked by dpo");

    ClassificationService.AutoResult first = classifications.autoClassify("pg_prod");
    assertEquals(7, first.columnsConsidered());
    // phone_number/full_name/home_city/balance get AUTO rows; email was already set
    assertEquals(4, first.created());
    assertEquals(1, first.alreadyClassified());

    Classification manual = classifications.get("pg_prod", CUSTOMER + ".email").orElseThrow();
    assertEquals("OTHER", manual.category());
    assertEquals("LOW", manual.level());
    assertEquals(Classification.SOURCE_MANUAL, manual.source());
    assertEquals("checked by dpo", manual.note());

    Classification phone = classifications.get("pg_prod", CUSTOMER + ".phone_number").orElseThrow();
    assertEquals("CONTACT", phone.category());
    assertEquals("HIGH", phone.level());
    assertEquals(Classification.SOURCE_AUTO, phone.source());
    // no heuristic hit, no row
    assertTrue(classifications.get("pg_prod", CUSTOMER + ".order_id").isEmpty());

    // second pass: everything is already classified, nothing changes
    ClassificationService.AutoResult second = classifications.autoClassify("pg_prod");
    assertEquals(7, second.columnsConsidered());
    assertEquals(0, second.created());
    assertEquals(5, second.alreadyClassified());
    assertEquals(5, classifications.list("pg_prod").size());
  }

  @Test
  void autoClassifyLowercasesColumnKeys() {
    structures.replace("pg_prod", List.of(
        new TableStructure("CRM", "Public", "Customer", List.of(
            new TableStructure.ColumnStructure("Phone", "varchar")))));
    classifications.autoClassify("pg_prod");
    assertTrue(classifications.get("pg_prod", "crm.public.customer.phone").isPresent());
  }

  @Test
  void overviewAggregatesPerInstanceAndGlobally() {
    classifications.autoClassify("pg_prod");
    classifications.autoClassify("my_prod");

    ClassificationService.Overview overview = classifications.overview();
    assertEquals(7, overview.totalClassified());
    assertEquals(3, overview.high());
    assertEquals(3, overview.medium());
    assertEquals(1, overview.low());
    assertEquals(2, overview.instances().size());

    ClassificationService.InstanceStat my = overview.instances().get(0);
    assertEquals("my_prod", my.instance());
    assertEquals(3, my.columns());
    assertEquals(2, my.classified());
    assertEquals(1, my.high());
    assertEquals(1, my.medium());
    assertEquals(0, my.low());

    ClassificationService.InstanceStat pg = overview.instances().get(1);
    assertEquals("my_prod", my.instance());
    assertEquals(3, my.columns());
    assertEquals(2, my.classified());
    assertEquals(1, my.high());
    assertEquals(1, my.medium());
    assertEquals(0, my.low());
  }

  @Test
  void manualUpsertNormalizesAndOverwrites() {
    Classification created = classifications.upsert("pg_prod", CUSTOMER + ".ORDER_ID",
        "finance", "high", "  billing anchor  ");
    assertEquals(CUSTOMER + ".order_id", created.columnKey());
    assertEquals("FINANCE", created.category());
    assertEquals("HIGH", created.level());
    assertEquals(Classification.SOURCE_MANUAL, created.source());
    assertEquals("billing anchor", created.note());
    assertNotNull(created.updatedAt());

    Classification updated = classifications.upsert("pg_prod", CUSTOMER + ".order_id",
        "OTHER", "low", null);
    assertEquals("OTHER", updated.category());
    assertEquals("LOW", updated.level());
    assertEquals(1, classifications.list("pg_prod").size());
    assertNotNull(updated.updatedAt());
  }

  @Test
  void upsertRejectsUnknownCategoryLevelAndKeyShape() {
    SqlMaskException category = assertThrows(SqlMaskException.class,
        () -> classifications.upsert("pg_prod", CUSTOMER + ".id", "SECRET", "HIGH", null));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, category.getCode());
    SqlMaskException level = assertThrows(SqlMaskException.class,
        () -> classifications.upsert("pg_prod", CUSTOMER + ".id", "PII", "EXTREME", null));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, level.getCode());
    SqlMaskException shape = assertThrows(SqlMaskException.class,
        () -> classifications.upsert("pg_prod", "not-four-parts", "PII", "HIGH", null));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, shape.getCode());
  }

  @Test
  void deleteReportsWhetherARowExisted() {
    classifications.autoClassify("pg_prod");
    assertTrue(classifications.delete("pg_prod", CUSTOMER + ".phone_number"));
    assertFalse(classifications.delete("pg_prod", CUSTOMER + ".phone_number"));
    assertTrue(classifications.get("pg_prod", CUSTOMER + ".phone_number").isEmpty());
  }

  @Test
  void unknownInstanceRejectedEverywhere() {
    SqlMaskException list = assertThrows(SqlMaskException.class,
        () -> classifications.list("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, list.getCode());
    SqlMaskException auto = assertThrows(SqlMaskException.class,
        () -> classifications.autoClassify("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, auto.getCode());
    SqlMaskException upsert = assertThrows(SqlMaskException.class,
        () -> classifications.upsert("ghost", CUSTOMER + ".id", "PII", "HIGH", null));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, upsert.getCode());
  }
}
