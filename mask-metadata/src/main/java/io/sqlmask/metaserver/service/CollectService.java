package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.web.MetadataDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pulls live metadata through the stored connection reference and replaces the
 * instance structure atomically. A failed collection never touches the stored
 * structure (spec §6: the source of the data-plane self-consistency).
 */
@Service
public class CollectService {

  private final MetadataService instances;
  private final StructureService structures;
  private final CredentialResolver credentials;
  private final IntrospectorFactory introspectors;

  public CollectService(MetadataService instances, StructureService structures,
      CredentialResolver credentials, IntrospectorFactory introspectors) {
    this.instances = instances;
    this.structures = structures;
    this.credentials = credentials;
    this.introspectors = introspectors;
  }

  public MetadataDtos.CollectResponse collect(String name) {
    InstanceRow row = instances.get(name);
    ConnectionInfo connection = row.connection();
    if (connection == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + name + "' has no connection settings; PUT the connection first");
    }
    ConnectionSpec spec = new ConnectionSpec(row.dialect(), connection.host(), connection.port(),
        connection.database(), connection.dbUser(), credentials.resolve(connection.passwordRef()),
        connection.schemas(), connection.includeViews(), false, connection.sslmode(),
        connection.connectTimeoutSeconds());
    IntrospectionResult result = introspectors.byEngine(row.dialect()).introspect(spec);
    List<TableStructure> tables = toStructures(result);
    long version = structures.replace(name, tables);
    return new MetadataDtos.CollectResponse(tables.size(),
        tables.stream().mapToInt(t -> t.columns().size()).sum(), result.warnings(), version);
  }

  private static List<TableStructure> toStructures(IntrospectionResult result) {
    List<TableStructure> tables = new ArrayList<>();
    for (IntrospectionResult.TableInfo table : result.tables()) {
      List<TableStructure.ColumnStructure> columns = new ArrayList<>();
      for (IntrospectionResult.ColumnInfo column : table.columns()) {
        columns.add(new TableStructure.ColumnStructure(column.name(), column.yamlType()));
      }
      tables.add(new TableStructure(table.catalog(), table.schema(), table.name(), columns));
    }
    return tables;
  }
}
