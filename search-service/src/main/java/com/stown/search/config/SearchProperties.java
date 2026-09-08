package com.stown.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.search")
public class SearchProperties {

    private String index = "messages";
    private long reconcileIntervalMs = 60_000L;
    private int reconcileBatchSize = 100;
    private boolean reconcileBackfillEnabled = true;
    private int maxPageSize = 100;
    private int snippetLength = 240;
    private String topic = "message.ingested";
    private String deadLetterTopic = "message.ingested.dlt";
    private int retryAttempts = 3;
    private long retryInitialIntervalMs = 1_000L;
    private double retryMultiplier = 2.0d;
    private long retryMaxIntervalMs = 10_000L;
}
