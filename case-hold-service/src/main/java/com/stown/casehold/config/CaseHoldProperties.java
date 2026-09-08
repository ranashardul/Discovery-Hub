package com.stown.casehold.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.case-hold")
public class CaseHoldProperties {

    /** Kafka topic that carries every Case & Hold domain event. */
    private String topic = "case-hold.events";

    /** Maximum number of items returned by any list endpoint. */
    private int maxPageSize = 100;

    private final Outbox outbox = new Outbox();

    @Data
    public static class Outbox {
        private long initialDelayMs = 15_000L;
        private long intervalMs = 5_000L;
        private int batchSize = 100;
    }
}
