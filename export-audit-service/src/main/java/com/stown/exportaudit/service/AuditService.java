package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.AuditEventDocument;
import com.stown.exportaudit.messaging.AuditEvent;
import com.stown.exportaudit.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit service. Records are only ever inserted and read; there
 * are no update or delete methods, so historical events can never be silently
 * overwritten. The {@code eventId} is unique, which makes recording idempotent
 * under Kafka redelivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    /**
     * Records an audit event synchronously. Used internally by the export
     * service for export lifecycle events.
     */
    public AuditEventDocument record(
            String action,
            String caseId,
            String targetType,
            String targetId,
            String actor,
            String status,
            Map<String, Object> details
    ) {
        return record(AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .caseId(caseId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .actor(actor)
                .status(status)
                .timestamp(Instant.now())
                .details(details)
                .build());
    }

    /**
     * Records a transition audit event synchronously, carrying structured
     * before/after state. Used by callers that change an entity's state and
     * need to record what it was before and what it is now.
     */
    public AuditEventDocument record(
            String action,
            String caseId,
            String targetType,
            String targetId,
            String actor,
            String status,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> details
    ) {
        return record(AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .caseId(caseId)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .actor(actor)
                .status(status)
                .timestamp(Instant.now())
                .before(before)
                .after(after)
                .details(details)
                .build());
    }

    /**
     * Records an audit event from a Kafka message. Idempotent on
     * {@code eventId}: a redelivered event is logged and the previously stored
     * document is returned without a duplicate insert.
     */
    public AuditEventDocument record(AuditEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            event.setEventId(UUID.randomUUID().toString());
        }

        AuditEventDocument document = AuditEventDocument.builder()
                .id(UUID.randomUUID().toString())
                .eventId(event.getEventId())
                .caseId(event.getCaseId())
                .action(event.getAction())
                .targetType(event.getTargetType())
                .targetId(event.getTargetId())
                .actor(event.getActor())
                .status(event.getStatus())
                .timestamp(event.getTimestamp() == null ? Instant.now() : event.getTimestamp())
                .before(event.getBefore())
                .after(event.getAfter())
                .details(event.getDetails())
                .receivedAt(Instant.now())
                .build();

        try {
            auditEventRepository.insert(document);

            log.info(
                    "Recorded audit event eventId={} action={} caseId={} targetId={} status={}",
                    document.getEventId(),
                    document.getAction(),
                    document.getCaseId(),
                    document.getTargetId(),
                    document.getStatus()
            );

            return document;
        } catch (DuplicateKeyException exception) {
            log.info(
                    "Ignoring duplicate audit event eventId={} action={}",
                    document.getEventId(),
                    document.getAction()
            );

            return auditEventRepository.findByEventId(document.getEventId())
                    .orElse(document);
        }
    }

    /** Returns the audit history for a case, newest first. */
    public List<AuditEventDocument> findByCaseId(String caseId) {
        return auditEventRepository.findByCaseIdOrderByTimestampDesc(caseId);
    }

    /** Returns the audit history for a specific target entity, newest first. */
    public List<AuditEventDocument> findByTargetId(String targetId) {
        return auditEventRepository.findByTargetIdOrderByTimestampDesc(targetId);
    }
}
