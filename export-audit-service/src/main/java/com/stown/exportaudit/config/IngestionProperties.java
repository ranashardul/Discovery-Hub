package com.stown.exportaudit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionProperties {

    /** Base URL of the ingestion service read API (message content by id). */
    private String baseUrl = "http://localhost:8081";

    /** Read timeout for fetching message content. */
    private int readTimeoutMs = 5000;

    /** Connection timeout for fetching message content. */
    private int connectTimeoutMs = 2000;
}
