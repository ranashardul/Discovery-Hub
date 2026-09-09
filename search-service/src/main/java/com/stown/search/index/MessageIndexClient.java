package com.stown.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import com.stown.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Thin wrapper around the official Elasticsearch Java client that owns the
 * index lifecycle and the low level document operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageIndexClient {

    /** Elasticsearch default for {@code index.max_result_window}. */
    private static final int MAX_PAGE_SIZE = 10_000;

    private final ElasticsearchClient elasticsearchClient;
    private final SearchProperties properties;

    private volatile boolean indexReady;

    public String indexName() {
        return properties.getIndex();
    }

    /**
     * Creates the index with an explicit mapping when it is absent. Safe to
     * call repeatedly and from multiple threads.
     */
    public synchronized void ensureIndex() throws IOException {
        if (indexReady) {
            return;
        }

        String index = indexName();
        boolean exists = elasticsearchClient.indices()
                .exists(request -> request.index(index))
                .value();

        if (exists) {
            indexReady = true;
            return;
        }

        try {
            elasticsearchClient.indices().create(request -> request
                    .index(index)
                    .mappings(mapping -> mapping
                            .properties("messageId", property -> property.keyword(keyword -> keyword))
                            .properties("deduplicationKey", property -> property.keyword(keyword -> keyword))
                            .properties("externalMessageId", property -> property.keyword(keyword -> keyword))
                            .properties("communicationType", property -> property.keyword(keyword -> keyword))
                            .properties("threadId", property -> property.keyword(keyword -> keyword))
                            .properties("sender", property -> property.text(text -> text
                                    .fields("keyword", field -> field.keyword(keyword -> keyword.ignoreAbove(256)))))
                            .properties("recipients", property -> property.text(text -> text
                                    .fields("keyword", field -> field.keyword(keyword -> keyword.ignoreAbove(256)))))
                            .properties("subject", property -> property.text(text -> text
                                    .fields("keyword", field -> field.keyword(keyword -> keyword.ignoreAbove(256)))))
                            .properties("body", property -> property.text(text -> text))
                            .properties("attachmentFilenames", property -> property.text(text -> text
                                    .fields("keyword", field -> field.keyword(keyword -> keyword.ignoreAbove(256)))))
                            .properties("messageTimestamp", property -> property.date(date -> date))
                            .properties("indexedAt", property -> property.date(date -> date))
                            .properties("attachmentCount", property -> property.integer(integer -> integer))
                            .properties("holdCount", property -> property.integer(integer -> integer))
                            .properties("holdIds", property -> property.keyword(keyword -> keyword))
                            .properties("dispositionStatus", property -> property.keyword(keyword -> keyword))));

            log.info("Created Elasticsearch index index={}", index);
        } catch (Exception exception) {
            boolean createdConcurrently = elasticsearchClient.indices()
                    .exists(request -> request.index(index))
                    .value();

            if (!createdConcurrently) {
                throw exception;
            }
        }

        indexReady = true;
    }

    public void index(SearchDocument document) throws IOException {
        ensureIndex();
        elasticsearchClient.index(request -> request
                .index(indexName())
                .id(document.getMessageId())
                .document(document));
    }

    public SearchDocument get(String messageId) throws IOException {
        ensureIndex();
        GetResponse<SearchDocument> response = elasticsearchClient.get(
                request -> request.index(indexName()).id(messageId),
                SearchDocument.class
        );

        return response.found() ? response.source() : null;
    }

    /**
     * Removes a document by id. Disposition events are delivered at least once,
     * so deleting an absent document is treated as success rather than an
     * error.
     *
     * @return true when a document was actually removed
     */
    public boolean delete(String messageId) throws IOException {
        ensureIndex();
        DeleteResponse response = elasticsearchClient.delete(request -> request
                .index(indexName())
                .id(messageId));

        return response.result() == Result.Deleted;
    }

    public boolean exists(String messageId) throws IOException {
        ensureIndex();
        return elasticsearchClient
                .exists(request -> request.index(indexName()).id(messageId))
                .value();
    }

    /**
     * Returns up to {@code size} document ids in ascending id order, starting
     * after {@code afterId}.
     *
     * <p>Uses {@code search_after} rather than {@code from}/{@code size}
     * because deep paging is capped at 10,000 by {@code index.max_result_window},
     * and this walks the entire index. Sources are not fetched: only the id is
     * needed.
     *
     * @param afterId id to resume after, or null to start from the beginning
     */
    public List<String> listMessageIds(String afterId, int size) throws IOException {
        ensureIndex();

        // index.max_result_window caps a single page at 10,000 by default, and
        // that applies to size even with search_after. Exceeding it fails the
        // whole request, which would disable a caller that pages through the
        // index rather than just returning fewer results, so clamp instead.
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if (pageSize < size) {
            log.warn("Requested page size {} exceeds the {} limit; using {}", size, MAX_PAGE_SIZE, pageSize);
        }

        SearchResponse<Void> response = elasticsearchClient.search(request -> {
            request.index(indexName())
                    .query(query -> query.matchAll(matchAll -> matchAll))
                    .size(pageSize)
                    .source(source -> source.fetch(false))
                    .sort(sort -> sort.field(field -> field
                            .field("messageId")
                            .order(SortOrder.Asc)));

            if (afterId != null) {
                request.searchAfter(value -> value.stringValue(afterId));
            }

            return request;
        }, Void.class);

        return response.hits().hits().stream()
                .map(Hit::id)
                .filter(Objects::nonNull)
                .toList();
    }

    public long count() throws IOException {
        ensureIndex();
        return elasticsearchClient.count(request -> request.index(indexName())).count();
    }

    public void refresh() throws IOException {
        elasticsearchClient.indices().refresh(request -> request.index(indexName()));
    }

    public SearchResponse<SearchDocument> search(
            Query query,
            List<SortOptions> sort,
            int from,
            int size,
            List<String> highlightFields
    ) throws IOException {
        ensureIndex();
        return elasticsearchClient.search(request -> {
            request.index(indexName())
                    .query(query)
                    .from(from)
                    .size(size)
                    .trackTotalHits(track -> track.enabled(true));

            if (!sort.isEmpty()) {
                request.sort(sort);
            }

            List<NamedValue<HighlightField>> fields = highlightFields.stream()
                    .map(field -> NamedValue.of(
                            field,
                            HighlightField.of(config -> config.fragmentSize(200).numberOfFragments(1))
                    ))
                    .toList();

            request.highlight(highlight -> highlight.fields(fields));

            return request;
        }, SearchDocument.class);
    }
}
