package com.stown.ingestion.api;

import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.IngestionStatus;

import java.time.Instant;

public record IngestionRequestStatusResponse(
        String requestId,
        String deduplicationKey,
        String externalMessageId,
        String messageId,
        IngestionStatus status,
        int attempts,
        String lastError,
        int attachmentCount,
        Instant createdAt,
        Instant updatedAt
) {

    public static IngestionRequestStatusResponse from(IngestionRequestDocument document) {
        return new IngestionRequestStatusResponse(
                document.getRequestId(),
                document.getDeduplicationKey(),
                document.getExternalMessageId(),
                document.getMessageId(),
                document.getStatus(),
                document.getAttempts(),
                document.getLastError(),
                document.getStagedAttachments() == null
                        ? 0
                        : document.getStagedAttachments().size(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
