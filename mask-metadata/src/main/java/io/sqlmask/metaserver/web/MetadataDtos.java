package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.TableStructure;

import java.util.List;

/** Request/response records of the metadata admin and data planes. */
public final class MetadataDtos {

  private MetadataDtos() {
  }

  public record ConnectionRequest(String host, Integer port, String database, String dbUser,
      String passwordRef, String sslmode, Integer connectTimeoutSeconds, List<String> schemas,
      boolean includeViews) {
  }

  public record InstanceCreateRequest(String name, String dialect, ConnectionRequest connection) {
  }

  public record InstanceUpdateRequest(ConnectionRequest connection) {
  }

  public record InstanceImportRequest(String name, String dialect, ConnectionRequest connection,
      String metadataYaml) {
  }

  public record InstanceSummaryResponse(String name, String dialect, long metadataVersion) {
  }

  public record InstanceDetailResponse(String name, String dialect, long metadataVersion,
      ConnectionInfo connection, List<TableStructure> tables) {
  }

  public record ImportResponse(String name, int tableCount, int columnCount,
      long metadataVersion) {
  }

  public record CollectResponse(int tableCount, int columnCount, List<String> warnings,
      long metadataVersion) {
  }
}
