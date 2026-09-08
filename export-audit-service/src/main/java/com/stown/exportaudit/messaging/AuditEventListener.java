package com.stown.exportaudit.messaging;

import com.stown.exportaudit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code audit.events} published by other services and records them
 * in the append-only audit store. Redelivered events are idempotent because the
 * audit service deduplicates on {@code eventId}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditService auditService;

    @KafkaListener(
            topics = "${app.export.audit-topic}",
            groupId = "${spring.kafka.consumer.group-id:export-audit-service}"
    )
    public void onAuditEvent(AuditEvent event) {
        if (event == null) {
            log.warn("Discarding null audit event");
            return;
        }

        log.debug(
                "Received audit.events eventId={} action={} caseId={}",
                event.getEventId(),
                event.getAction(),
                event.getCaseId()
        );

        auditService.record(event);
    }
}
