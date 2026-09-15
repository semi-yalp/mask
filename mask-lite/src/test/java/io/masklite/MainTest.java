package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI argument handling of {@link Main#run(String[])}: usage guards and the happy path. */
class MainTest {

  private static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: mask_phone
      """;

  @TempDir
  Path temp;

  private Path metadataFile() throws IOException {
    Path file = temp.resolve("metadata.yaml");
    Files.writeString(file, METADATA);
    return file;
  }

  @Test
  void rewritesSqlGivenOnTheCommandLine() throws IOException {
    String output = Main.run(new String[] {
        "--metadata", metadataFile().toString(),
        "--sql", "SELECT phone FROM crm.public.customer"});
    assertTrue(output.contains("mask_phone(r.phone, 3, 4)"), () -> output);
  }

  @Test
  void metadataFlagRequiresAValue() {
    RuntimeException e = assertThrows(RuntimeException.class,
        () -> Main.run(new String[] {"--metadata"}));
    assertTrue(e.getMessage().contains("--metadata requires a file path"), () -> e.getMessage());
  }

  @Test
  void sqlFlagRequiresAValue() throws IOException {
    RuntimeException e = assertThrows(RuntimeException.class,
        () -> Main.run(new String[] {"--metadata", metadataFile().toString(), "--sql"}));
    assertTrue(e.getMessage().contains("--sql requires an argument"), () -> e.getMessage());
  }

  @Test
  void unknownArgumentIsRejected() {
    RuntimeException e = assertThrows(RuntimeException.class,
        () -> Main.run(new String[] {"--nope"}));
    assertTrue(e.getMessage().contains("unknown argument '--nope'"), () -> e.getMessage());
  }

  @Test
  void metadataIsRequired() {
    RuntimeException e = assertThrows(RuntimeException.class,
        () -> Main.run(new String[] {"--sql", "SELECT 1"}));
    assertTrue(e.getMessage().contains("--metadata is required"), () -> e.getMessage());
  }

  @Test
  void missingMetadataFileIsIoError() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> Main.run(new String[] {
            "--metadata", temp.resolve("absent.yaml").toString(),
            "--sql", "SELECT 1"}));
    assertEquals(SqlMaskException.Code.IO_ERROR, e.getCode());
  }
}
