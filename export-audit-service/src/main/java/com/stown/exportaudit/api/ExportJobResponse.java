package com.stown.exportaudit.api;

import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.domain.ExportStatus;

import java.time.Instant;

/**
 * Response for export job creation and status lookup. Carries the job metadata
 * described in {@code Export-Audit-services.md}: export ID, case ID, status,
 * file location and timestamps.
 */
public record ExportJobResponse(
        String exportId,
        String caseId,
        String holdId,
        ExportScope scope,
        String requestedBy,
        ExportStatus status,
        String s3Bucket,
        String s3Key,
        long packageSizeBytes,
        String packageSha256,
        int messageCount,
        int attachmentCount,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {

    public static ExportJobResponse from(ExportJobDocument job) {
        return new ExportJobResponse(
                job.getExportId(),
                job.getCaseId(),
                job.getHoldId(),
                job.getScope(),
                job.getRequestedBy(),
                job.getStatus(),
                job.getS3Bucket(),
                job.getS3Key(),
                job.getPackageSizeBytes(),
                job.getPackageSha256(),
                job.getMessageCount(),
                job.getAttachmentCount(),
                job.getAttempts(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getUpdatedAt()
        );
    }
}
