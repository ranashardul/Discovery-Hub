package com.stown.ingestion.api;

import com.stown.ingestion.domain.MessageDocument;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Retention and hold state for a single message. Read-only; useful for
 * demonstrating that a countdown is running and that a hold is protecting a
 * message.
 */
public record RetentionStatusResponse(
        String messageId,
        String externalMessageId,
        String communicationType,
        Instant createdAt,
        Instant retentionUntil,
        long secondsUntilExpiry,
        boolean retentionExpired,
        boolean held,
        int holdCount,
        List<String> holdIds,
        String dispositionStatus
) {

    public static RetentionStatusResponse from(MessageDocument message, Instant now) {
        Instant retentionUntil = message.getRetentionUntil();
        long seconds = retentionUntil == null
                ? Long.MAX_VALUE
                : Duration.between(now, retentionUntil).toSeconds();

        return new RetentionStatusResponse(
                message.getId(),
                message.getExternalMessageId(),
                message.getCommunicationType(),
                message.getCreatedAt(),
                retentionUntil,
                Math.max(seconds, 0),
                retentionUntil != null && !retentionUntil.isAfter(now),
                message.getHoldCount() > 0,
                message.getHoldCount(),
                message.getHoldIds() == null ? List.of() : message.getHoldIds(),
                message.getDispositionStatus()
        );
    }
}
