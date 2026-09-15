package io.sqlmask.udfseed;

import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Remote smoke-test seeding only: creates the {@code pg_prod} instance in the
 * web app's in-memory policy store so the UDF REST endpoints have an instance
 * to work against (instance CRUD REST does not exist yet). Attached at runtime
 * via PropertiesLauncher {@code loader.path}; no production class is modified.
 * Idempotent: a restart against the same (empty) store simply re-creates it.
 */
@Component
public class UdfSeedRunner implements CommandLineRunner {

  private final PolicyService service;

  public UdfSeedRunner(PolicyService service) {
    this.service = service;
  }

  @Override
  public void run(String... args) {
    try {
      service.createInstance("pg_prod", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(
              new ColumnDef("phone", "varchar"),
              new ColumnDef("email", "varchar"),
              new ColumnDef("name", "varchar"),
              new ColumnDef("idcard", "varchar")))));
      System.out.println("[udf-seed] instance 'pg_prod' created (crm.public.customer)");
    } catch (Exception alreadyExists) {
      System.out.println("[udf-seed] instance 'pg_prod' already present: "
          + alreadyExists.getMessage());
    }
  }
}
