package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.AttachmentMetadata;
import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.DispositionOutcome;
import com.stown.ingestion.domain.DispositionRun;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.messaging.MessageDisposedEvent;
import com.stown.ingestion.repository.DispositionAuditRepository;
import com.stown.ingestion.repository.DispositionRunRepository;
import com.stown.ingestion.repository.IngestionRequestRepository;
import com.stown.ingestion.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deletes messages whose retention has expired, except those under legal hold
 * (PRD FR-5.2), and records every run (FR-5.3).
 *
 * <h2>Why not a TTL index</h2>
 * A MongoDB TTL index has no conditional predicate, so it would delete held
 * messages, leave the S3 objects orphaned, bypass the audit trail and never
 * notify search. Retention has to be enforced in the application.
 *
 * <h2>Ordering</h2>
 * The audit record is written <em>before</em> the document is removed, holding
 * the object keys. If the process dies mid-purge, the keys are still known and
 * {@link PurgeSweeper} finishes the job. Removing the document first would
 * strand the binaries with nothing pointing at them.
 *
 * <h2>Hold safety</h2>
 * The delete is a conditional {@code findAndRemove} filtered on
 * {@code holdCount = 0}, so the hold check and the delete are one atomic
 * operation. A hold arriving after the candidate was read causes the delete to
 * match nothing rather than destroying held evidence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispositionService {

    public static final String MESSAGE_DISPOSED_TOPIC = "message.disposed";

    private static final String REASON_RETENTION = "RETENTION";
    private static final String REASON_MANUAL = "MANUAL";

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    /** Guards against two passes competing for the same candidates. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final MessageRepository messageRepository;
    private final IngestionRequestRepository requestRepository;
    private final DispositionAuditRepository auditRepository;
    private final DispositionRunRepository runRepository;
    private final S3StorageService storageService;
    private final MongoTemplate mongoTemplate;
    private final KafkaTemplate<String, MessageDisposedEvent> kafkaTemplate;
    private final RetentionProperties properties;
    private final AuditPublisher auditPublisher;

    @Scheduled(
            initialDelayString = "${app.retention.disposition-interval-ms:60000}",
            fixedDelayString = "${app.retention.disposition-interval-ms:60000}"
    )
    public void runScheduledDisposition() {
        if (!properties.isEnabled()) {
            return;
        }

        disposeExpired(TRIGGER_SCHEDULED);
    }

    /**
     * Runs one disposition pass on request rather than on the timer.
     *
     * <p>Refuses to run concurrently with the scheduler or with another manual
     * request. Two passes over the same candidates would both try to delete
     * them, and the second would count failures for work the first had already
     * done, so the run summary would misreport what happened.
     *
     * @throws DispositionInProgressException when a pass is already running
     */
    public DispositionRun disposeOnRequest() {
        if (!properties.isEnabled()) {
            throw new DispositionDisabledException();
        }

        return disposeExpired(TRIGGER_MANUAL);
    }

    /** Runs one disposition pass and returns its record. Also callable from tests. */
    public DispositionRun disposeExpired() {
        return disposeExpired(TRIGGER_SCHEDULED);
    }

    private DispositionRun disposeExpired(String trigger) {
        if (!running.compareAndSet(false, true)) {
            throw new DispositionInProgressException();
        }

        try {
            return runPass(trigger);
        } finally {
            running.set(false);
        }
    }

    private DispositionRun runPass(String trigger) {
        Instant now = Instant.now();
        DispositionRun run = DispositionRun.builder()
                .runId(UUID.randomUUID().toString())
                .startedAt(now)
                .trigger(trigger)
                .dryRun(properties.isDryRun())
                .build();

        List<MessageDocument> candidates = messageRepository.findByRetentionUntilLessThanEqual(
                now,
                PageRequest.of(0, properties.getBatchSize(), Sort.by("retentionUntil"))
        );

        run.setScanned(candidates.size());

        for (MessageDocument candidate : candidates) {
            if (run.getDeleted() >= properties.getMaxDeletesPerRun()) {
                run.setDeleteCapReached(true);
                log.warn(
                        "Disposition run {} stopped at the delete cap of {}; remaining"
                                + " candidates will be handled on the next run",
                        run.getRunId(),
                        properties.getMaxDeletesPerRun()
                );
                break;
            }

            try {
                applyTo(candidate, run, now);
            } catch (RuntimeException exception) {
                run.setFailed(run.getFailed() + 1);
                run.setLastError(describe(exception));
                log.error(
                        "Disposition failed messageId={} runId={}",
                        candidate.getId(),
                        run.getRunId(),
                        exception
                );
            }
        }

        run.setFinishedAt(Instant.now());

        if (run.getScanned() > 0) {
            runRepository.save(run);

            auditPublisher.dispositionRunCompleted(
                    run.getRunId(),
                    run.getTrigger(),
                    run.getScanned(),
                    run.getDeleted(),
                    run.getSkippedOnHold(),
                    run.getFailed(),
                    run.isDryRun()
            );

            log.info(
                    "Disposition run {} scanned={} deleted={} skippedOnHold={} failed={}"
                            + " objectsPurged={} dryRun={}",
                    run.getRunId(),
                    run.getScanned(),
                    run.getDeleted(),
                    run.getSkippedOnHold(),
                    run.getFailed(),
                    run.getS3ObjectsPurged(),
                    run.isDryRun()
            );
        }

        return run;
    }

    private void applyTo(MessageDocument candidate, DispositionRun run, Instant now) {
        if (candidate.getHoldCount() > 0) {
            recordSkipped(candidate, run.getRunId());
            run.setSkippedOnHold(run.getSkippedOnHold() + 1);
            return;
        }

        if (properties.isDryRun()) {
            log.info(
                    "[dry-run] would dispose messageId={} retentionUntil={} attachments={}",
                    candidate.getId(),
                    candidate.getRetentionUntil(),
                    attachmentKeys(candidate).size()
            );
            run.setDeleted(run.getDeleted() + 1);
            return;
        }

        DispositionAudit audit = auditRepository.save(intent(candidate, run.getRunId(), REASON_RETENTION));

        MessageDocument removed = conditionalRemove(candidate.getId(), now);

        if (removed == null) {
            // A hold landed between reading the candidate and deleting it.
            audit.setOutcome(DispositionOutcome.SKIPPED_ON_HOLD);
            audit.setCompletedAt(Instant.now());
            auditRepository.save(audit);

            run.setSkippedOnHold(run.getSkippedOnHold() + 1);
            log.info(
                    "Skipped messageId={} - a hold was applied during the run",
                    candidate.getId()
            );
            return;
        }

        int purged = completeDisposition(removed, audit, REASON_MANUAL.equals(audit.getReason())
                ? REASON_MANUAL
                : REASON_RETENTION);

        run.setDeleted(run.getDeleted() + 1);
        run.setS3ObjectsPurged(run.getS3ObjectsPurged() + purged);
        run.setSearchPurgePublished(run.getSearchPurgePublished() + (audit.isEventPublished() ? 1 : 0));
    }

    /**
     * Deletes a message on request rather than on expiry.
     *
     * @throws HeldMessageDeletionException when a legal hold covers it, which
     *         is the demonstrable proof required by FR-4.6
     */
    public DispositionAudit deleteOnRequest(String messageId) {
        MessageDocument message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException(messageId));

        if (message.getHoldCount() > 0) {
            auditPublisher.deletionBlocked(
                    messageId,
                    message.getHoldCount(),
                    message.getHoldIds()
            );

            throw new HeldMessageDeletionException(
                    messageId,
                    message.getHoldCount(),
                    message.getHoldIds()
            );
        }

        DispositionAudit audit = auditRepository.save(intent(message, null, REASON_MANUAL));

        // Unconditional on retention, still conditional on the hold count, so a
        // hold placed a moment ago cannot be overridden by a manual delete.
        MessageDocument removed = mongoTemplate.findAndRemove(
                Query.query(Criteria.where("_id").is(messageId).and("holdCount").is(0)),
                MessageDocument.class
        );

        if (removed == null) {
            audit.setOutcome(DispositionOutcome.SKIPPED_ON_HOLD);
            audit.setCompletedAt(Instant.now());
            auditRepository.save(audit);

            // A hold landed between the read above and this delete.
            auditPublisher.deletionBlocked(messageId, 1, message.getHoldIds());

            throw new HeldMessageDeletionException(messageId, 1, message.getHoldIds());
        }

        int purged = completeDisposition(removed, audit, REASON_MANUAL);

        auditPublisher.messageDeleted(messageId, REASON_MANUAL, purged);

        return audit;
    }

    /** Purges objects, publishes the event and closes the audit record. */
    int completeDisposition(MessageDocument removed, DispositionAudit audit, String reason) {
        int purged = purgeObjects(removed);

        audit.setOutcome(DispositionOutcome.DELETED);
        audit.setObjectsPurged(true);
        audit.setCompletedAt(Instant.now());

        boolean published = publishDisposed(removed, reason, purged);
        audit.setEventPublished(published);

        auditRepository.save(audit);

        markRequestDisposed(removed);

        log.info(
                "Disposed messageId={} reason={} objectsPurged={} eventPublished={}",
                removed.getId(),
                reason,
                purged,
                published
        );

        return purged;
    }

    private MessageDocument conditionalRemove(String messageId, Instant now) {
        return mongoTemplate.findAndRemove(
                Query.query(Criteria.where("_id").is(messageId)
                        .and("holdCount").is(0)
                        .and("retentionUntil").lte(now)),
                MessageDocument.class
        );
    }

    int purgeObjects(MessageDocument message) {
        int purged = 0;

        for (String key : attachmentKeys(message)) {
            try {
                storageService.delete(key);
                purged++;
            } catch (RuntimeException exception) {
                log.error(
                        "Failed to purge object key={} messageId={}",
                        key,
                        message.getId(),
                        exception
                );
                throw exception;
            }
        }

        return purged;
    }

    private boolean publishDisposed(MessageDocument message, String reason, int purged) {
        MessageDisposedEvent event = MessageDisposedEvent.builder()
                .eventId(UUID.randomUUID())
                .messageId(message.getId())
                .externalMessageId(message.getExternalMessageId())
                .communicationType(message.getCommunicationType())
                .reason(reason)
                .attachmentsPurged(purged)
                .retentionUntil(message.getRetentionUntil())
                .disposedAt(Instant.now())
                .build();

        try {
            kafkaTemplate.send(MESSAGE_DISPOSED_TOPIC, message.getId(), event).join();
            return true;
        } catch (RuntimeException exception) {
            // The message is already gone; the sweeper retries publication so
            // the search projection is not left holding deleted content.
            log.error(
                    "Failed to publish {} messageId={}",
                    MESSAGE_DISPOSED_TOPIC,
                    message.getId(),
                    exception
            );
            return false;
        }
    }

    /**
     * Marks the originating request DISPOSED so a re-submission is not silently
     * treated as a duplicate of a message that no longer exists.
     */
    private void markRequestDisposed(MessageDocument message) {
        if (message.getRequestId() == null) {
            return;
        }

        requestRepository.findById(message.getRequestId()).ifPresent(request -> {
            request.setStatus(IngestionStatus.DISPOSED);
            request.setUpdatedAt(Instant.now());
            requestRepository.save(request);
        });
    }

    private void recordSkipped(MessageDocument message, String runId) {
        DispositionAudit audit = intent(message, runId, REASON_RETENTION);
        audit.setOutcome(DispositionOutcome.SKIPPED_ON_HOLD);
        audit.setCompletedAt(Instant.now());

        auditRepository.save(audit);

        log.info(
                "Skipped messageId={} retentionUntil={} holdCount={} holdIds={}",
                message.getId(),
                message.getRetentionUntil(),
                message.getHoldCount(),
                message.getHoldIds()
        );
    }

    private DispositionAudit intent(MessageDocument message, String runId, String reason) {
        return DispositionAudit.builder()
                .id(UUID.randomUUID().toString())
                .messageId(message.getId())
                .runId(runId)
                .externalMessageId(message.getExternalMessageId())
                .communicationType(message.getCommunicationType())
                .outcome(DispositionOutcome.PENDING)
                .reason(reason)
                .retentionUntil(message.getRetentionUntil())
                .holdCountAtDecision(message.getHoldCount())
                .holdIdsAtDecision(message.getHoldIds())
                .s3Bucket(storageService.getBucket())
                .s3Keys(attachmentKeys(message))
                .decidedAt(Instant.now())
                .build();
    }

    private List<String> attachmentKeys(MessageDocument message) {
        List<String> keys = new ArrayList<>();
        List<AttachmentMetadata> attachments = message.getAttachments();

        if (attachments == null) {
            return keys;
        }

        for (AttachmentMetadata attachment : attachments) {
            Optional.ofNullable(attachment.getS3Key()).ifPresent(keys::add);
        }

        return keys;
    }

    private String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
