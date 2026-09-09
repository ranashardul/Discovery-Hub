package com.stown.exportaudit.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Request to record an audit event directly through the API. Other services
 * can also publish onto the {@code audit.events} Kafka topic; this endpoint is
 * for synchronous recording (for example from services without Kafka access).
 */
@Data
public class AuditRequest {

    /** Unique id for idempotency. If omitted, the service assigns one. */
    private String eventId;

    private String caseId;

    @NotBlank
    private String action;

    /** {@code CASE}, {@code EXPORT}, {@code EVIDENCE}, {@code HOLD}, etc. */
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
