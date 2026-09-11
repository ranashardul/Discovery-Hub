package com.stown.exportaudit.messaging;

import com.stown.exportaudit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Consumes {@code audit.events} published by other services and records them
 * in the append-only audit store. Redelivered events are idempotent because the
 * audit service deduplicates on {@code eventId}.
 *
 * <p>Events from ingestion (e.g. {@code DELETION_BLOCKED}) arrive without a
 * {@code caseId} because that service knows only the holdIds on a message, not
 * the case they belong to. This listener enriches such events by resolving the
 * caseId from the holdId via the audit store, so the event appears in the
 * case's audit trail when the export report is built.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditService auditService;

    @KafkaListener(
            topics = "${app.export.audit-topic}",
            groupId = "${spring.kafka.consumer.group-id:export-audit-service}",
            containerFactory = "auditEventListenerFactory"
    )
    public void onAuditEvent(AuditEvent event) {
        if (event == null) {
            log.warn("Discarding null audit event");
            return;
        }

        enrichCaseIdFromHoldIds(event);

        log.debug(
                "Received audit.events eventId={} action={} caseId={}",
                event.getEventId(),
                event.getAction(),
                event.getCaseId()
        );

        auditService.record(event);
    }

    /**
     * If the event has no caseId but carries holdIds in its details, resolve
     * the caseId from the first holdId found in the audit store. This links
     * ingestion events (DELETION_BLOCKED, MESSAGE_DELETED) to the case whose
     * hold caused or was affected by the action.
     */
    @SuppressWarnings("unchecked")
    private void enrichCaseIdFromHoldIds(AuditEvent event) {
        if (event.getCaseId() != null && !event.getCaseId().isBlank()) {
            return;
        }
        if (event.getDetails() == null) {
            return;
        }

        Object holdIdsRaw = event.getDetails().get("holdIds");
        if (!(holdIdsRaw instanceof List<?> holdIds) || holdIds.isEmpty()) {
            return;
        }

        for (Object holdId : holdIds) {
            if (!(holdId instanceof String hid) || hid.isBlank()) {
                continue;
            }
            String caseId = auditService.findCaseIdByHoldId(hid);
            if (caseId != null) {
                event.setCaseId(caseId);
                log.info(
                        "Enriched audit event action={} targetId={} caseId={} from holdId={}",
                        event.getAction(),
                        event.getTargetId(),
                        caseId,
                        hid
                );
                return;
            }
        }
    }
}
