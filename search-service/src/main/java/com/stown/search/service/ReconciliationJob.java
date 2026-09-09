package com.stown.search.service;

import com.stown.search.config.SearchProperties;
import com.stown.search.domain.MessageDocument;
import com.stown.search.domain.SearchIndexFailure;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.repository.MessageRepository;
import com.stown.search.repository.SearchIndexFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Safety net for the &lt;30s searchability target: retries recorded indexing
 * failures, back-fills messages that exist in MongoDB but are missing from
 * Elasticsearch (for instance after an event was lost), and removes documents
 * whose message no longer exists at all.
 *
 * <p>The back-fill deliberately looks only at the most recent
 * {@code reconcile-batch-size} messages. It exists to close small, fresh gaps
 * cheaply on every cycle, not to populate an index from scratch - scanning the
 * whole collection every minute would be wasteful and would still lag on a
 * large corpus. Use {@link ReindexService} to walk the entire collection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private final SearchIndexFailureRepository failureRepository;
    private final MessageRepository messageRepository;
    private final MessageIndexClient indexClient;
    private final IndexingService indexingService;
    private final SearchProperties properties;

    /** Resume point for the orphan sweep, so each cycle continues where the last stopped. */
    private volatile String orphanCursor;

    @Scheduled(
            fixedDelayString = "${app.search.reconcile-interval-ms:60000}",
            initialDelayString = "${app.search.reconcile-interval-ms:60000}"
    )
    public void reconcile() {
        int retried = retryRecordedFailures();
        int backfilled = backfillMissingDocuments();
        int orphansRemoved = sweepOrphans();

        if (retried > 0 || backfilled > 0 || orphansRemoved > 0) {
            log.info(
                    "Reconciliation completed retried={} backfilled={} orphansRemoved={}",
                    retried,
                    backfilled,
                    orphansRemoved
            );
        }
    }

    /**
     * Deletes documents whose message no longer exists in MongoDB.
     *
     * <p>The disposition listener already removes messages as they are
     * disposed, but that only works for events this service actually receives.
     * A dropped event, a consumer that was down during the disposition, or a
     * deletion made against a shared database by a process publishing to a
     * different broker all leave a document behind. That document stays fully
     * searchable by subject and body after the record was destroyed, which is
     * the failure this sweep exists to catch. The back-fill covers the same
     * risk in the opposite direction.
     *
     * <p>Walks the index a batch per cycle using a cursor rather than scanning
     * everything each minute, so the cost per run stays flat regardless of
     * corpus size. On reaching the end the cursor resets and the walk repeats.
     *
     * <p>Deletes only ids MongoDB positively reported as absent. If the lookup
     * itself fails the sweep aborts without deleting anything: a database
     * error must never be mistaken for "these messages were disposed".
     */
    private int sweepOrphans() {
        if (!properties.isOrphanSweepEnabled()) {
            return 0;
        }

        List<String> indexedIds;
        try {
            indexedIds = indexClient.listMessageIds(orphanCursor, properties.getOrphanSweepBatchSize());
        } catch (Exception exception) {
            log.error("Could not list indexed ids for the orphan sweep reason={}", exception.getMessage());
            return 0;
        }

        if (indexedIds.isEmpty()) {
            orphanCursor = null;
            return 0;
        }

        Set<String> present;
        try {
            present = StreamSupport
                    .stream(messageRepository.findAllById(indexedIds).spliterator(), false)
                    .map(MessageDocument::getId)
                    .collect(Collectors.toSet());
        } catch (Exception exception) {
            // Deleting on a failed lookup would destroy the index.
            log.error("Could not check messages during the orphan sweep reason={}", exception.getMessage());
            return 0;
        }

        int removed = 0;
        for (String messageId : indexedIds) {
            if (present.contains(messageId)) {
                continue;
            }

            try {
                if (indexClient.delete(messageId)) {
                    removed++;
                    log.warn(
                            "Removed orphaned document messageId={} index={} "
                                    + "(absent from MongoDB, disposition event never applied)",
                            messageId,
                            indexClient.indexName()
                    );
                }
            } catch (Exception exception) {
                log.warn("Orphan removal failed messageId={} reason={}", messageId, exception.getMessage());
            }
        }

        // A short page means the end of the index; start again next cycle.
        orphanCursor = indexedIds.size() < properties.getOrphanSweepBatchSize()
                ? null
                : indexedIds.getLast();

        return removed;
    }

    private int retryRecordedFailures() {
        List<SearchIndexFailure> failures;
        try {
            failures = failureRepository.findByResolvedFalseAndAbandonedFalse(
                    PageRequest.of(0, properties.getReconcileBatchSize(), Sort.by("lastFailedAt"))
            );
        } catch (Exception exception) {
            log.error("Could not load indexing failures reason={}", exception.getMessage());
            return 0;
        }

        int retried = 0;
        for (SearchIndexFailure failure : failures) {
            try {
                indexingService.indexMessage(failure.getMessageId(), failure.getEventId());
                retried++;
            } catch (Exception exception) {
                log.warn(
                        "Reconciliation retry failed messageId={} attempts={} reason={}",
                        failure.getMessageId(),
                        failure.getAttempts(),
                        exception.getMessage()
                );
            }
        }

        return retried;
    }

    private int backfillMissingDocuments() {
        if (!properties.isReconcileBackfillEnabled()) {
            return 0;
        }

        List<MessageDocument> candidates;
        try {
            candidates = messageRepository.findAll(
                    PageRequest.of(
                            0,
                            properties.getReconcileBatchSize(),
                            Sort.by(Sort.Direction.DESC, "createdAt")
                    )
            ).getContent();
        } catch (Exception exception) {
            log.error("Could not load messages for back-fill reason={}", exception.getMessage());
            return 0;
        }

        int backfilled = 0;
        for (MessageDocument message : candidates) {
            try {
                if (indexClient.exists(message.getId())) {
                    continue;
                }
                indexingService.indexMessage(message.getId(), null);
                backfilled++;
            } catch (Exception exception) {
                log.warn(
                        "Back-fill failed messageId={} reason={}",
                        message.getId(),
                        exception.getMessage()
                );
            }
        }

        return backfilled;
    }
}
