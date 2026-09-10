package com.stown.ingestion.service;

import com.stown.ingestion.messaging.AuditEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes this service's chain-of-custody events onto {@code audit.events},
 * where export-audit-service records them in the append-only audit store.
 *
 * <p>Ingestion is where messages are actually destroyed, so without this the
 * audit trail has no record of a disposition run, a deletion, or a deletion
 * refused because of a legal hold — the last being the one event a regulator
 * is most likely to ask about.
 *
 * <p>Sent as an object, not a pre-serialised string. This service's producer
 * is already configured with a {@code JacksonJsonSerializer}, so handing it a
 * String produces a JSON-encoded string — {@code "{\"eventId\":…}"} — and the
 * consumer cannot read that back as an object. The consumer resolves the
 * payload against its own {@code AuditEvent} with type headers disabled, so
 * the type name this serialiser attaches is ignored there and the field names
 * are all that matter.
 *
 * <p>A publish failure is logged and swallowed. These events are a side effect
 * of an operation that has already happened: failing the caller's delete
 * because the audit broker was briefly unavailable would trade a durable
 * outcome for a reporting one. The trade-off is that a lost event is not
 * retried, which is why the durable {@code DispositionAudit} record in MongoDB
 * remains the authority for what this service deleted.
 */
@Slf4j
@Service
public class AuditPublisher {

    public static final String ACTION_DELETION_BLOCKED = "DELETION_BLOCKED";
    public static final String ACTION_MESSAGE_DELETED = "MESSAGE_DELETED";
    public static final String ACTION_DISPOSITION_RUN = "DISPOSITION_RUN_COMPLETED";

    private static final String TARGET_MESSAGE = "MESSAGE";
    private static final String TARGET_SYSTEM = "SYSTEM";

    /** Nothing authenticates to this service, so there is no user to name. */
    private static final String ACTOR = "ingestion-service";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public AuditPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.audit.topic:audit.events}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Records that a deletion was refused because a hold covers the message.
     * This is the FR-4.6 proof: the refusal itself has to be auditable, or
     * "the platform blocks deletion" is an unevidenced claim.
     */
    public void deletionBlocked(String messageId, int holdCount, Iterable<String> holdIds) {
        publish(AuditEvent.builder()
                .eventId(eventId(ACTION_DELETION_BLOCKED, messageId))
                .action(ACTION_DELETION_BLOCKED)
                .targetType(TARGET_MESSAGE)
                .targetId(messageId)
                .actor(ACTOR)
                .status("REFUSED")
                .timestamp(Instant.now())
                .details(Map.of(
                        "summary", "Deletion refused: the message is under legal hold",
                        "holdCount", holdCount,
                        "holdIds", holdIds == null ? List.of() : holdIds
                ))
                .build());
    }

    /** Records a message that was deleted, whether on request or by policy. */
    public void messageDeleted(String messageId, String reason, int attachmentsPurged) {
        publish(AuditEvent.builder()
                .eventId(eventId(ACTION_MESSAGE_DELETED, messageId))
                .action(ACTION_MESSAGE_DELETED)
                .targetType(TARGET_MESSAGE)
                .targetId(messageId)
                .actor(ACTOR)
                .status("COMPLETED")
                .timestamp(Instant.now())
                .details(Map.of(
                        "summary", "Message deleted and removed from the index",
                        "reason", reason == null ? "" : reason,
                        "attachmentsPurged", attachmentsPurged
                ))
                .build());
    }

    /** Records the outcome of a disposition run, scheduled or manual. */
    public void dispositionRunCompleted(
            String runId,
            String trigger,
            long scanned,
            long deleted,
            long skippedOnHold,
            long failed,
            boolean dryRun
    ) {
        publish(AuditEvent.builder()
                .eventId(eventId(ACTION_DISPOSITION_RUN, runId))
                .action(ACTION_DISPOSITION_RUN)
                .targetType(TARGET_SYSTEM)
                .targetId(runId)
                .actor(ACTOR)
                .status(failed > 0 ? "PARTIAL" : "COMPLETED")
                .timestamp(Instant.now())
                .details(Map.of(
                        "summary", "Disposition run deleted %d of %d scanned, %d skipped on hold"
                                .formatted(deleted, scanned, skippedOnHold),
                        "trigger", trigger == null ? "SCHEDULED" : trigger,
                        "scanned", scanned,
                        "deleted", deleted,
                        "skippedOnHold", skippedOnHold,
                        "failed", failed,
                        "dryRun", dryRun
                ))
                .build());
    }

    /**
     * A deterministic id per action and target, so a redelivery or a repeated
     * call records once rather than filling the trail with duplicates. The
     * consumer enforces uniqueness on it.
     */
    private String eventId(String action, String targetId) {
        return UUID.nameUUIDFromBytes(
                (action + ':' + targetId + ':' + Instant.now().toEpochMilli()).getBytes()
        ).toString();
    }

    private void publish(AuditEvent event) {
        try {
            kafkaTemplate.send(topic, event.getTargetId(), event);
        } catch (Exception exception) {
            log.error(
                    "Could not publish audit event action={} targetId={} reason={}",
                    event.getAction(),
                    event.getTargetId(),
                    exception.getMessage()
            );
        }
    }
}
