package com.stown.search.service;

import com.stown.search.api.ReindexResponse;
import com.stown.search.config.SearchProperties;
import com.stown.search.domain.MessageDocument;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.index.SearchDocumentMapper;
import com.stown.search.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Walks the whole {@code messages} collection and indexes it.
 *
 * <p>Needed because neither of the two normal paths can populate an index from
 * an existing corpus. Event-driven indexing only fires for newly ingested
 * messages, and anything already published has no further events coming;
 * {@link ReconciliationJob}'s back-fill deliberately looks only at the newest
 * batch. Pointing the service at a database that already holds messages - a
 * shared cluster, a restored backup - would otherwise leave all but the most
 * recent handful invisible, with nothing logged to say so.
 *
 * <p>Also the supported way to rebuild after an index mapping change, where
 * the index has to be dropped and repopulated from MongoDB.
 *
 * <p>Runs one at a time. A second request while a walk is in progress is
 * rejected rather than queued, so two passes cannot compete for the same
 * documents.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    private final MessageRepository messageRepository;
    private final SearchDocumentMapper documentMapper;
    private final MessageIndexClient indexClient;
    private final SearchProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isRunning() {
        return running.get();
    }

    /**
     * @param force when false, documents already present in the index are
     *              skipped, which makes a re-run cheap after a partial pass.
     *              When true every message is re-indexed, which is what a
     *              mapping change needs.
     */
    public ReindexResponse reindexAll(boolean force) {
        if (!running.compareAndSet(false, true)) {
            throw new ReindexInProgressException();
        }

        Instant startedAt = Instant.now();
        long scanned = 0;
        long indexed = 0;
        long skipped = 0;
        long failed = 0;

        try {
            // The index may have been dropped since the last write — that is
            // the documented way to apply a mapping change, and this endpoint
            // is what repopulates it afterwards. Without discarding the cached
            // "index exists" flag, creation is skipped and the first write
            // makes Elasticsearch auto-create the index with a dynamic
            // mapping, quietly breaking every exact-match filter.
            indexClient.invalidateIndexCache();
            indexClient.ensureIndex();

            int pageSize = properties.getReindexBatchSize();
            // Sorting by _id gives a stable total order, so paging cannot skip
            // or repeat a document the way an unsorted scan can.
            Page<MessageDocument> page = messageRepository.findAll(
                    PageRequest.of(0, pageSize, Sort.by(Sort.Direction.ASC, "_id"))
            );
            long totalPages = page.getTotalPages();

            log.info(
                    "Reindex started force={} totalMessages={} pages={} pageSize={}",
                    force,
                    page.getTotalElements(),
                    totalPages,
                    pageSize
            );

            for (int pageNumber = 0; pageNumber < totalPages; pageNumber++) {
                if (pageNumber > 0) {
                    page = messageRepository.findAll(
                            PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.ASC, "_id"))
                    );
                }

                for (MessageDocument message : page.getContent()) {
                    scanned++;
                    try {
                        if (!force && indexClient.exists(message.getId())) {
                            skipped++;
                            continue;
                        }

                        indexClient.index(documentMapper.toSearchDocument(message, Instant.now()));
                        indexed++;
                    } catch (Exception exception) {
                        failed++;
                        log.warn(
                                "Reindex failed for message messageId={} reason={}",
                                message.getId(),
                                exception.getMessage()
                        );
                    }
                }

                log.info(
                        "Reindex progress page={}/{} scanned={} indexed={} skipped={} failed={}",
                        pageNumber + 1,
                        totalPages,
                        scanned,
                        indexed,
                        skipped,
                        failed
                );
            }

            // Without an explicit refresh the newly written documents are not
            // visible to search until Elasticsearch's next refresh interval,
            // which makes the response look wrong to anyone checking straight
            // after the call returns.
            indexClient.refresh();
        } catch (Exception exception) {
            log.error("Reindex aborted scanned={} reason={}", scanned, exception.getMessage());
            throw new SearchExecutionException("Reindex failed", exception);
        } finally {
            running.set(false);
        }

        long elapsedMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        log.info(
                "Reindex completed scanned={} indexed={} skipped={} failed={} elapsedMs={}",
                scanned,
                indexed,
                skipped,
                failed,
                elapsedMs
        );

        return new ReindexResponse(scanned, indexed, skipped, failed, elapsedMs);
    }
}
