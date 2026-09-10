package com.stown.ingestion.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Chain-of-custody event published onto {@code audit.events} for
 * export-audit-service to record.
 *
 * <p>This is an independent copy of the contract that service declares in
 * {@code com.stown.exportaudit.messaging.AuditEvent}. Contracts are duplicated
 * rather than shared across these services, so the field names here must match
 * that class exactly: its consumer binds the payload by field name with no type
 * header, so a rename produces no compile error and simply reads {@code null}.
 * See the contract-drift note in AGENTS.md.
 *
 * <p>{@code eventId} must be stable for a given logical event, not generated
 * per delivery attempt: the consumer deduplicates on it, which is what makes
 * an at-least-once redelivery safe.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    private String eventId;
    private String caseId;
    private String action;
    private String targetType;
    private String targetId;
    private String actor;
    private String status;
    private Instant timestamp;

    /** Structured before-state snapshot, for transition events. */
    private Map<String, Object> before;

    /** Structured after-state snapshot, paired with {@code before}. */
    private Map<String, Object> after;

    private Map<String, Object> details;
}
