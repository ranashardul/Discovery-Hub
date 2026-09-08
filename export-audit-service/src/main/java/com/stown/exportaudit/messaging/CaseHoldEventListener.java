package com.stown.exportaudit.messaging;

import com.stown.exportaudit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumes domain events from the Case & Hold service and records them in the
 * append-only audit trail. The Case & Hold service publishes events onto the
 * {@code case-hold.events} topic with an {@code eventType} field carrying the
 * action name (CASE_CREATED, HOLD_CREATED, etc.).
 *
 * <p>This listener converts each event into an {@link AuditEvent} and delegates
 * to {@link AuditService}, which is idempotent on {@code eventId} so redelivered
 * events are safely deduplicated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseHoldEventListener {

    private final AuditService auditService;

    @KafkaListener(
            topics = "${app.case-hold.events-topic}",
            groupId = "${spring.kafka.consumer.group-id:export-audit-service}"
    )
    public void onCaseHoldEvent(Map<String, Object> event) {
        if (event == null || event.get("eventType") == null) {
            log.warn("Discarding case-hold event without eventType: {}", event);
            return;
        }

        String eventType = String.valueOf(event.get("eventType"));
        String caseId = event.get("caseId") == null ? null : String.valueOf(event.get("caseId"));
        String holdId = event.get("holdId") == null ? null : String.valueOf(event.get("holdId"));
        String eventId = event.get("eventId") == null ? null : String.valueOf(event.get("eventId"));
        String actor = event.get("createdBy") == null
                ? (event.get("updatedBy") == null
                        ? (event.get("releasedBy") == null ? null : String.valueOf(event.get("releasedBy")))
                        : String.valueOf(event.get("updatedBy")))
                : String.valueOf(event.get("createdBy"));

        // Build audit details from the remaining event fields.
        Map<String, Object> details = new LinkedHashMap<>(event);
        details.remove("eventType");
        details.remove("eventId");
        details.remove("caseId");

        String targetId = holdId != null ? holdId : caseId;
        String targetType = holdId != null ? "HOLD" : "CASE";

        AuditEvent auditEvent = AuditEvent.builder()
                .eventId(eventId)
                .caseId(caseId)
                .action(eventType)
                .targetType(targetType)
                .targetId(targetId)
                .actor(actor)
                .status("COMPLETED")
                .timestamp(Instant.now())
                .details(details)
                .build();

        auditService.record(auditEvent);

        log.info(
                "Recorded case-hold audit event type={} caseId={} holdId={} eventId={}",
                eventType, caseId, holdId, eventId
        );
    }
}
