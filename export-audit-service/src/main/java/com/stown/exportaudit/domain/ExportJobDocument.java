package com.stown.exportaudit.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable record of an export job. Created by the API in {@code QUEUED} state,
 * advanced to {@code RUNNING} by the worker and finally to {@code COMPLETED} or
 * {@code FAILED}. The document is append-in-spirit: status transitions are
 * monotonic and the durable package location is set only once, on completion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "export_jobs")
public class ExportJobDocument {

    @Id
    private String exportId;

    @Indexed(name = "caseId_idx")
    private String caseId;

    /** Hold ID, set when scope is LEGAL_HOLD. */
    private String holdId;

    private ExportScope scope;

    /** Originating actor of the export request. */
    private String requestedBy;

    private String communicationType;
    private String sender;
    private String threadId;
    private Instant fromTimestamp;
    private Instant toTimestamp;

    private ExportStatus status;

    private int attempts;
    private String lastError;

    /** Package location, populated on completion. */
    private String s3Bucket;
    private String s3Key;
    private long packageSizeBytes;
    private String packageSha256;
    private int messageCount;
    private int attachmentCount;

    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;
}
