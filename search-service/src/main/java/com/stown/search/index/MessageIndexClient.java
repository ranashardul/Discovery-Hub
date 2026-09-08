package com.stown.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.util.NamedValue;
import com.stown.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Thin wrapper around the official Elasticsearch Java client that owns the
 * index lifecycle and the low level document operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageIndexClient {

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
                            .properties("attachmentCount", property -> property.integer(integer -> integer))));

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

    public boolean exists(String messageId) throws IOException {
        ensureIndex();
        return elasticsearchClient
                .exists(request -> request.index(indexName()).id(messageId))
                .value();
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
