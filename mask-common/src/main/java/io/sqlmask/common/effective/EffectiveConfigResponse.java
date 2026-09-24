package io.sqlmask.common.effective;

import java.util.List;
import java.util.Map;

/**
 * Wire contract of the policy service data plane: the compiled effective
 * configuration, structurally equivalent to the YAML the rewrite engine
 * already consumes. Shared by mask-core (consumer) and mask-policy-server
 * (producer); lives in mask-common so neither service depends on the other.
 */
public record EffectiveConfigResponse(String instance, String dialect, long configVersion,
    PolicySummary policySummary, ConfigPayload config) {

  public record PolicySummary(int enabled, int disabled) {
  }

  public record ConfigPayload(MetadataPayload metadata, List<ColumnBinding> columns,
      Map<String, UdfDefinition> policies) {
  }

  public record MetadataPayload(List<TablePayload> tables) {
  }

  public record TablePayload(String catalog, String schema, String name, String rowFilter,
      List<ColumnPayload> columns) {
  }

  public record ColumnPayload(String name, String type) {
  }

  public record ColumnBinding(String catalog, String schema, String table, String column,
      String policy, boolean inheritOnCopy) {

    /** 兼容既有调用:未声明继承即 false。 */
    public ColumnBinding(String catalog, String schema, String table, String column, String policy) {
      this(catalog, schema, table, column, policy, false);
    }
  }

  public record UdfDefinition(String udf, List<Object> arguments) {
  }
}
