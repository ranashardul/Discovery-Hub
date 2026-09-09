package com.stown.exportaudit.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only audit record. The repository exposes only insert and read
 * operations, and there are no update/delete APIs, so historical events can
 * never be overwritten. The {@code eventId} is unique so the Kafka consumer can
 * redeliver an event without creating a duplicate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_events")
public class AuditEventDocument {

    @Id
    private String id;

    @Indexed(unique = true, name = "audit_eventId_unique")
    private String eventId;

    @Indexed(name = "audit_caseId_idx")
    private String caseId;

    @Indexed(name = "audit_action_idx")
    private String action;

    @Indexed(name = "audit_targetType_idx")
    private String targetType;

    @Indexed(name = "audit_targetId_idx")
    private String targetId;

    private String actor;
    private String status;
    private Instant timestamp;

    /**
     * Structured before-state snapshot, for transition events (for example a
     * case status change from {@code ACTIVE} to {@code UNDER_REVIEW}). Kept
     * separate from {@link #details} so consumers can read it predictably.
     */
    private Map<String, Object> before;

    /**
     * Structured after-state snapshot, paired with {@link #before}. Never
     * mutated after insert.
     */
    private Map<String, Object> after;

    /** Free-form metadata; never mutated after insert. */
    private Map<String, Object> details;

    private Instant receivedAt;
}
