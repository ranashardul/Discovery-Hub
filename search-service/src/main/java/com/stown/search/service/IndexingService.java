package com.stown.search.service;

import com.stown.search.domain.MessageDocument;
import com.stown.search.domain.SearchIndexFailure;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.index.SearchDocument;
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

    public long pendingFailureCount() {
        return failureRepository.countByResolvedFalse();
    }

    private void markResolved(String messageId) {
        failureRepository.findById(messageId).ifPresent(failure -> {
            if (!failure.isResolved()) {
                failure.setResolved(true);
                failure.setResolvedAt(Instant.now());
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
