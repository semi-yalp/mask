package io.sqlmask.server.grant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dialect compilation of the unified grant model. */
class GrantCompilerTest {

  private final GrantCompiler compiler = new GrantCompiler();

  private GrantEntry entry(GrantEntry.PrincipalType pt, String principal,
      GrantEntry.ResourceType rt, String resourceId, GrantEntry.Privilege privilege) {
    return new GrantEntry(0, "pg_prod", pt, principal, rt, resourceId, privilege, null, null);
  }

  @Test
  void postgresTableGrantQuotesAndQualifies() {
    List<GrantCompiler.CompiledStatement> out = compiler.compile("postgresql",
        List.of(entry(GrantEntry.PrincipalType.USER, "alice", GrantEntry.ResourceType.TABLE,
            "crm.public.customer", GrantEntry.Privilege.SELECT)));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).sql())
        .isEqualTo("GRANT SELECT ON \"crm\".\"public\".\"customer\" TO \"alice\";");
  }

  @Test
  void postgresGroupCreatesRoleAndGrantsToIt() {
    List<GrantCompiler.CompiledStatement> out = compiler.compile("postgresql",
        List.of(entry(GrantEntry.PrincipalType.GROUP, "analysts", GrantEntry.ResourceType.SCHEMA,
            "crm.public", GrantEntry.Privilege.SELECT)));
    assertThat(out).hasSize(2);
    assertThat(out.get(0).sql()).isEqualTo("CREATE ROLE \"g_analysts\" NOLOGIN;");
    assertThat(out.get(1).sql())
        .isEqualTo("GRANT USAGE ON SCHEMA \"crm\".\"public\" TO \"g_analysts\";");
  }

  @Test
  void mysqlSchemaGrant() {
    List<GrantCompiler.CompiledStatement> out = compiler.compile("mysql",
        List.of(entry(GrantEntry.PrincipalType.USER, "alice", GrantEntry.ResourceType.SCHEMA,
            "crm.public", GrantEntry.Privilege.ALL)));
    assertThat(out.get(0).sql())
        .isEqualTo("GRANT ALL PRIVILEGES ON `crm`.`public`.* TO 'alice'@'%';");
  }

  @Test
  void hiveProducesPreviewCommentsOnly() {
    List<GrantCompiler.CompiledStatement> out = compiler.compile("hive",
        List.of(entry(GrantEntry.PrincipalType.USER, "alice", GrantEntry.ResourceType.TABLE,
            "crm.default.customer", GrantEntry.Privilege.SELECT)));
    assertThat(out.get(0).sql()).startsWith("-- hive preview:");
  }

  @Test
  void unsafePrincipalIsRefused() {
    // a normal principal compiles fine
    assertThat(compiler.compile("postgresql",
        List.of(entry(GrantEntry.PrincipalType.USER, "alice", GrantEntry.ResourceType.TABLE,
            "crm.public.customer", GrantEntry.Privilege.SELECT)))).hasSize(1);
    GrantEntry evil = new GrantEntry(0, "pg", GrantEntry.PrincipalType.USER, "a; DROP TABLE x",
        GrantEntry.ResourceType.TABLE, "crm.public.customer", GrantEntry.Privilege.SELECT, null,
        null);
    assertThatThrownBy(() -> compiler.compile("postgresql", List.of(evil)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
