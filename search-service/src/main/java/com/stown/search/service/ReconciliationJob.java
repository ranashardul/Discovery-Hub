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

/**
 * Safety net for the &lt;30s searchability target: retries recorded indexing
 * failures and back-fills messages that exist in MongoDB but are missing from
 * Elasticsearch (for instance after an event was lost).
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

    @Scheduled(
            fixedDelayString = "${app.search.reconcile-interval-ms:60000}",
            initialDelayString = "${app.search.reconcile-interval-ms:60000}"
    )
    public void reconcile() {
        int retried = retryRecordedFailures();
        int backfilled = backfillMissingDocuments();

        if (retried > 0 || backfilled > 0) {
            log.info("Reconciliation completed retried={} backfilled={}", retried, backfilled);
        }
    }

    private int retryRecordedFailures() {
        List<SearchIndexFailure> failures;
        try {
            failures = failureRepository.findByResolvedFalse(
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
