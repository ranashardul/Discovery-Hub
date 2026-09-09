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
    private String disposedTopic = "message.disposed";
    private String disposedDeadLetterTopic = "message.disposed.dlt";
    private String disposedConsumerGroup = "search-service-disposed";

    /**
     * Indexing attempts before a failure ledger entry is abandoned and stops
     * being retried. Zero disables the cap.
     */
    private int failureMaxAttempts = 10;

    /** Page size used when walking MongoDB during a full reindex. */
    private int reindexBatchSize = 500;

    private int retryAttempts = 3;
    private long retryInitialIntervalMs = 1_000L;
    private double retryMultiplier = 2.0d;
    private long retryMaxIntervalMs = 10_000L;
}
