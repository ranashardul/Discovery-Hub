package com.stown.exportaudit.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Audit event consumed from Kafka. Other services publish onto the
 * {@code audit.events} topic to record chain-of-custody events without a
 * direct call to this service. The audit consumer is idempotent on
 * {@code eventId}.
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
