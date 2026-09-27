package io.sqlmask.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MainTest {

  @Test
  void defaultsAreStdioAnd8084() {
    Main.Config cfg = Main.parseArgs(new String[0]);
    assertEquals("stdio", cfg.transport());
    assertEquals(8084, cfg.port());
  }

  @Test
  void parsesTransportAndPort() {
    Main.Config cfg = Main.parseArgs(new String[] {"--transport", "http", "--port", "9090"});
    assertEquals("http", cfg.transport());
    assertEquals(9090, cfg.port());
  }

  @Test
  void rejectsUnknownArg() {
    IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"--nope"}));
    assertEquals("unknown arg: --nope", e.getMessage());
  }

  /** 骨架冒烟：Schemas.parse 可解析合法 schema，specFor 能组装 SyncToolSpecification。 */
  @Test
  void specForAssemblesToolSpecification() {
    McpTool ping = new McpTool() {
      @Override public String name() { return "ping"; }
      @Override public String description() { return "smoke"; }
      @Override public String schemaJson() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}"; }
      @Override public io.modelcontextprotocol.spec.McpSchema.CallToolResult call(
          Map<String, Object> arguments) {
        throw new UnsupportedOperationException();
      }
    };
    Object spec = McpServerFactory.specFor(ping);
    assertNotNull(spec);
  }
}
