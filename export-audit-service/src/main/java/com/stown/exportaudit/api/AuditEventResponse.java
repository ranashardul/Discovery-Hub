package com.stown.exportaudit.api;

import com.stown.exportaudit.domain.AuditEventDocument;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only projection of an audit event. Audit records are append-only, so the
 * API exposes only the fields needed for display and never any mutation
 * endpoints. {@code before} and {@code after} carry the structured state
 * snapshots for transition events (for example a case status change).
 */
public record AuditEventResponse(
        String id,
        String eventId,
        String caseId,
        String action,
        String targetType,
        String targetId,
        String actor,
        String status,
        Instant timestamp,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> details,
        Instant receivedAt
) {

    public static AuditEventResponse from(AuditEventDocument document) {
        return new AuditEventResponse(
                document.getId(),
                document.getEventId(),
                document.getCaseId(),
                document.getAction(),
                document.getTargetType(),
                document.getTargetId(),
                document.getActor(),
                document.getStatus(),
                document.getTimestamp(),
                document.getBefore(),
                document.getAfter(),
                document.getDetails(),
                document.getReceivedAt()
        );
    }
}
