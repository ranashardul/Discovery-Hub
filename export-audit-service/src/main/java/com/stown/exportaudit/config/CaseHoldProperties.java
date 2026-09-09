package com.stown.exportaudit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.case-hold")
public class CaseHoldProperties {

    /** Base URL of the Case & Hold service. */
    private String baseUrl = "http://localhost:8083";

    /** Kafka topic carrying Case & Hold domain events. */
    private String eventsTopic = "case-hold.events";
}
