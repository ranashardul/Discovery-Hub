package com.stown.casehold.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the search service, used only to count what a hold
 * rule would cover before the hold is placed.
 */
@Data
@ConfigurationProperties(prefix = "app.search")
public class SearchProperties {

    private String baseUrl = "http://localhost:8082";

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 5000;
}
