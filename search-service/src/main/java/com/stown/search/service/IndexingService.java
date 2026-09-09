package com.stown.search.service;

import com.stown.search.domain.MessageDocument;
import com.stown.search.domain.SearchIndexFailure;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.index.SearchDocument;
import com.stown.search.config.SearchProperties;
import com.stown.search.index.SearchDocumentMapper;
import com.stown.search.repository.MessageRepository;
import com.stown.search.repository.SearchIndexFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private final MessageRepository messageRepository;
    private final SearchIndexFailureRepository failureRepository;
    private final SearchDocumentMapper documentMapper;
    private final MessageIndexClient indexClient;
    private final SearchProperties properties;

    /**
     * Loads the message from MongoDB and (re)indexes it. Indexing uses the
     * messageId as the Elasticsearch document id, so replays overwrite instead
     * of duplicating.
     */
    public void indexMessage(String messageId, String eventId) {
        long startedAt = System.nanoTime();

        try {
            MessageDocument message = messageRepository.findById(messageId)
                    .orElseThrow(() -> new MessageNotFoundException(messageId));

            SearchDocument document = documentMapper.toSearchDocument(message, Instant.now());
            indexClient.index(document);

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info(
                    "Indexed message messageId={} eventId={} index={} elapsedMs={}",
                    messageId,
                    eventId,
                    indexClient.indexName(),
                    elapsedMs
            );

            markResolved(messageId);
        } catch (Exception exception) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.error(
                    "Failed to index message messageId={} eventId={} index={} elapsedMs={} reason={}",
                    messageId,
                    eventId,
                    indexClient.indexName(),
                    elapsedMs,
                    exception.getMessage()
            );

            recordFailure(messageId, eventId, exception);
            throw exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException(exception);
        }
    }

    /**
     * Removes a disposed message from the index.
     *
     * <p>Disposition is the end of a message's life: ingestion has already
     * deleted it from MongoDB and purged its attachments, so leaving the
     * Elasticsearch document in place would keep the subject and body
     * searchable after the record was legally destroyed.
     *
     * <p>The event is delivered at least once, so this is idempotent -
     * deleting an absent document is a success, not an error. Any outstanding
     * failure ledger entry is dropped at the same time: the message can never
     * be indexed again, so retrying it forever would be pointless.
     */
    public void removeMessage(String messageId, String eventId, String reason) {
        long startedAt = System.nanoTime();

        try {
            boolean deleted = indexClient.delete(messageId);
            failureRepository.deleteById(messageId);

            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info(
                    "Disposed message removed from index messageId={} eventId={} reason={} "
                            + "index={} documentDeleted={} elapsedMs={}",
                    messageId,
                    eventId,
                    reason,
                    indexClient.indexName(),
                    deleted,
                    elapsedMs
            );
        } catch (Exception exception) {
            log.error(
                    "Failed to remove disposed message messageId={} eventId={} reason={} index={} reason2={}",
                    messageId,
                    eventId,
                    reason,
                    indexClient.indexName(),
                    exception.getMessage()
            );

            throw exception instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException(exception);
        }
    }

    public long pendingFailureCount() {
        return failureRepository.countByResolvedFalseAndAbandonedFalse();
    }

    public long abandonedFailureCount() {
        return failureRepository.countByAbandonedTrue();
    }

    private void markResolved(String messageId) {
        failureRepository.findById(messageId).ifPresent(failure -> {
            if (!failure.isResolved()) {
                failure.setResolved(true);
                failure.setResolvedAt(Instant.now());
                // A success clears abandonment: the message is indexed, so the
                // entry is history rather than an outstanding problem.
                failure.setAbandoned(false);
                failure.setAbandonedAt(null);
                failureRepository.save(failure);
            }
        });
    }

    private void recordFailure(String messageId, String eventId, Exception exception) {
        try {
            Instant now = Instant.now();
            SearchIndexFailure failure = failureRepository.findById(messageId)
                    .orElseGet(() -> SearchIndexFailure.builder()
                            .id(messageId)
                            .messageId(messageId)
                            .firstFailedAt(now)
                            .attempts(0)
                            .build());

            failure.setEventId(eventId);
            failure.setAttempts(failure.getAttempts() + 1);
            failure.setLastError(describe(exception));
            failure.setLastFailedAt(now);
            failure.setResolved(false);
            failure.setResolvedAt(null);

            // Past the cap the entry stops being retried. Without this a
            // message that can never succeed - one already disposed from
            // MongoDB, say - is retried on every reconciliation cycle forever
            // and pendingFailures grows without bound.
            int maxAttempts = properties.getFailureMaxAttempts();
            if (maxAttempts > 0 && failure.getAttempts() >= maxAttempts && !failure.isAbandoned()) {
                failure.setAbandoned(true);
                failure.setAbandonedAt(now);
                log.error(
                        "Abandoning message after repeated indexing failures messageId={} attempts={} lastError={}",
                        messageId,
                        failure.getAttempts(),
                        failure.getLastError()
                );
            }

            failureRepository.save(failure);
        } catch (Exception recordingFailure) {
            log.error(
                    "Could not record indexing failure messageId={} reason={}",
                    messageId,
                    recordingFailure.getMessage()
            );
        }
    }

    private String describe(Exception exception) {
        String message = exception.getMessage();
        String text = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
}
