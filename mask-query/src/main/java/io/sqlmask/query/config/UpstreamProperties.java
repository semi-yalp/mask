package io.sqlmask.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Upstream service locations; blank base URLs fail the query path closed. */
@ConfigurationProperties(prefix = "upstream")
public record UpstreamProperties(String metadataBaseUrl, String metadataApiKey,
    String rewriteBaseUrl, String rewriteApiKey) {}
