package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metadataclient.MetadataClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the rewrite-ready configuration for one query run: tables come fresh
 * from the metadata service snapshot, while row filters (which the policy
 * service bakes into its table payload) and the subject's column bindings and
 * policies come from the compiled effective config. A dialect disagreement
 * between the two services fails closed.
 */
@Component
public final class InstanceQueryAssembler {

  public LoadedConfig assemble(MetadataClient.MetadataSnapshot snapshot,
      ConfigSource.ResolvedConfig effective) {
    if (!snapshot.dialect().equals(effective.dialect())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + snapshot.instance() + "' reports dialect '" + snapshot.dialect()
              + "' but the effective config was compiled for '" + effective.dialect() + "'");
    }
    var typeResolver = DialectProfiles.byName(snapshot.dialect()).typeResolver();
    List<TableMetadata> tables = new ArrayList<>();
    int t = -1;
    for (MetadataClient.TableSnapshot ts : snapshot.tables()) {
      t++;
      String path = "instance '" + snapshot.instance() + "': tables[" + t + "] ("
          + ts.catalog() + "." + ts.schema() + "." + ts.name() + ")";
      List<TableMetadata.Column> columns = new ArrayList<>();
      try {
        for (MetadataClient.ColumnSnapshot cs : ts.columns()) {
          columns.add(typeResolver.parseColumn(cs.name(), cs.type()));
        }
      } catch (RuntimeException e) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, path + ": " + e.getMessage(), e);
      }
      String rowFilter = effective.config()
          .findTable(ts.catalog(), ts.schema(), ts.name())
          .map(TableMetadata::rowFilter).orElse(null);
      tables.add(new TableMetadata(ts.catalog(), ts.schema(), ts.name(), columns, rowFilter));
    }
    MaskingConfig effectiveConfig = effective.config().config();
    return new LoadedConfig(
        new MaskingConfig(tables, effectiveConfig.columnPolicies(), effectiveConfig.policies()));
  }
}
