package com.stown.exportaudit.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.export")
public class ExportProperties {

    /** Topic the API publishes export requests onto. */
    private String topic = "export.requested";

    /** Dead-letter topic for export requests that exhaust retries. */
    private String deadLetterTopic = "export.requested.dlt";

    /** Topic published by the worker when an export finishes (success/failure). */
    private String completedTopic = "export.completed";

    /** Topic other services publish audit events onto for this service to record. */
    private String auditTopic = "audit.events";
}
