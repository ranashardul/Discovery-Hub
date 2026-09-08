package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.stown.search.api.SearchResponse;
import com.stown.search.api.SearchResultItem;
import com.stown.search.api.SearchStatsResponse;
import com.stown.search.config.SearchProperties;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.index.SearchDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final List<String> HIGHLIGHT_FIELDS = List.of("subject", "body");

    private final MessageIndexClient indexClient;
    private final SearchQueryBuilder queryBuilder;
    private final IndexingService indexingService;
    private final SearchProperties properties;

    public SearchResponse search(SearchCriteria criteria) {
        long startedAt = System.nanoTime();
        Query query = queryBuilder.build(criteria);

        try {
            co.elastic.clients.elasticsearch.core.SearchResponse<SearchDocument> response =
                    indexClient.search(query, criteria.from(), criteria.size(), HIGHLIGHT_FIELDS);

            List<SearchResultItem> results = new ArrayList<>();
            for (Hit<SearchDocument> hit : response.hits().hits()) {
                SearchDocument document = hit.source();
                if (document == null) {
                    continue;
                }
                results.add(toResultItem(hit, document));
            }

            long total = response.hits().total() == null ? results.size() : response.hits().total().value();
            long tookMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            log.info(
                    "Search executed query=\"{}\" total={} from={} size={} tookMillis={}",
                    criteria.query(),
                    total,
                    criteria.from(),
                    criteria.size(),
                    tookMillis
            );

            return new SearchResponse(
                    criteria.query(),
                    total,
                    criteria.from(),
                    criteria.size(),
                    tookMillis,
                    results
            );
        } catch (IOException exception) {
            log.error("Search failed query=\"{}\" reason={}", criteria.query(), exception.getMessage());
            throw new SearchExecutionException("Search request failed", exception);
        }
    }

    public Optional<SearchDocument> findIndexedMessage(String messageId) {
        try {
            return Optional.ofNullable(indexClient.get(messageId));
        } catch (IOException exception) {
            log.error("Lookup failed messageId={} reason={}", messageId, exception.getMessage());
            throw new SearchExecutionException("Indexed message lookup failed", exception);
        }
    }

    public SearchStatsResponse stats() {
        try {
            return new SearchStatsResponse(
                    indexClient.count(),
                    indexClient.indexName(),
                    indexingService.pendingFailureCount()
            );
        } catch (IOException exception) {
            log.error("Stats lookup failed reason={}", exception.getMessage());
            throw new SearchExecutionException("Search statistics lookup failed", exception);
        }
    }

    private SearchResultItem toResultItem(Hit<SearchDocument> hit, SearchDocument document) {
        return new SearchResultItem(
                document.getMessageId(),
                hit.score(),
                document.getCommunicationType(),
                document.getSender(),
                document.getRecipients(),
                document.getSubject(),
                snippet(hit.highlight(), document.getBody()),
                document.getThreadId(),
                document.getMessageTimestamp(),
                document.getAttachmentCount(),
                document.getHoldCount() > 0,
                document.getDispositionStatus()
        );
    }

    private String snippet(Map<String, List<String>> highlight, String body) {
        if (highlight != null) {
            for (String field : HIGHLIGHT_FIELDS) {
                List<String> fragments = highlight.get(field);
                if (fragments != null && !fragments.isEmpty()) {
                    return fragments.getFirst();
                }
            }
        }

        return truncate(body);
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        int limit = properties.getSnippetLength();
        return body.length() <= limit ? body : body.substring(0, limit) + "...";
    }
}
