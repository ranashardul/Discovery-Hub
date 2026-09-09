package com.stown.ingestion.api;

import com.stown.ingestion.domain.AttachmentMetadata;
import com.stown.ingestion.domain.MessageDocument;

import java.time.Instant;
import java.util.List;

/**
 * Read projection of a stored message, returned by the ingestion read API.
 * Exposes only the fields downstream services (export, audit) need to build an
 * evidence package, never the ingestion-only outbox/retention state. This is
 * the contract other services use instead of reading the {@code messages}
 * MongoDB collection directly, keeping each service's data private (NFR-1).
 */
public record MessageResponse(
        String id,
        String deduplicationKey,
        String externalMessageId,
        String communicationType,
        String sender,
        List<String> recipients,
        String subject,
        String body,
        Instant messageTimestamp,
        String threadId,
        List<AttachmentMetadata> attachments,
        Instant createdAt
) {

    public static MessageResponse from(MessageDocument message) {
        return new MessageResponse(
                message.getId(),
                message.getDeduplicationKey(),
                message.getExternalMessageId(),
                message.getCommunicationType(),
                message.getSender(),
                message.getRecipients(),
                message.getSubject(),
                message.getBody(),
                message.getMessageTimestamp(),
                message.getThreadId(),
                message.getAttachments(),
                message.getCreatedAt()
        );
    }
}
