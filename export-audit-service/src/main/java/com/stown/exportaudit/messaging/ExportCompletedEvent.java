package com.stown.exportaudit.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published by the worker when an export finishes (success or failure). Other
 * services, and the audit pipeline, can subscribe to it without polling the
 * export-jobs collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportCompletedEvent {

    private String exportId;
    private String caseId;
    private String status;
    private String s3Bucket;
    private String s3Key;
    private long packageSizeBytes;
    private String packageSha256;
    private int messageCount;
    private int attachmentCount;

    private String errorMessage;
    private Instant completedAt;
}
