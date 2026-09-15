package io.sqlmask.server;

import io.sqlmask.metadataclient.MetadataClient;

/**
 * Thin seam over {@link MetadataClient} so import flows (and their tests) don't
 * depend on a live metadata service.
 */
public interface MetadataStructureFetcher {

  MetadataClient.MetadataSnapshot fetch(String baseUrl, String apiKey, String instance);

  /** Production impl: one-shot client per call. */
  class HttpMetadataStructureFetcher implements MetadataStructureFetcher {
    @Override
    public MetadataClient.MetadataSnapshot fetch(String baseUrl, String apiKey, String instance) {
      return new MetadataClient(baseUrl, apiKey).fetch(instance);
    }
  }
}
