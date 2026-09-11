package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.stown.search.api.CustodianResponse;
import com.stown.search.api.ResolvedIdsResponse;
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

    private static final String SUBJECT_FIELD = "subject";

    /**
     * Fields asked to produce a highlighted fragment.
     *
     * <p>The subject is highlighted in place and returned on its own, because
     * the result list renders it as the title. The rest feed the body
     * snippet, in this order: {@link #snippet} returns the first that
     * produced a fragment, so a hit matched only on a participant still shows
     * the reviewer why it matched instead of an unrelated opening line.
     */
    private static final List<String> HIGHLIGHT_FIELDS =
            List.of(SUBJECT_FIELD, "body", "sender", "recipients");

    private static final List<String> SNIPPET_FIELDS = List.of("body", "sender", "recipients");

    private final MessageIndexClient indexClient;
    private final SearchQueryBuilder queryBuilder;
    private final IndexingService indexingService;
    private final SearchProperties properties;

    public SearchResponse search(SearchCriteria criteria) {
        long startedAt = System.nanoTime();
        Query query = queryBuilder.build(criteria);
        List<SortOptions> sort = queryBuilder.sort(criteria);

        try {
            co.elastic.clients.elasticsearch.core.SearchResponse<SearchDocument> response =
                    indexClient.search(query, sort, criteria.from(), criteria.size(), HIGHLIGHT_FIELDS);

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
                    "Search executed query=\"{}\" total={} from={} size={} sort={} tookMillis={}",
                    criteria.query(),
                    total,
                    criteria.from(),
                    criteria.size(),
                    criteria.sort().value(),
                    tookMillis
            );

            return new SearchResponse(
                    criteria.query(),
                    total,
                    criteria.from(),
                    criteria.size(),
                    criteria.sort().value(),
                    tookMillis,
                    results
            );
        } catch (IOException exception) {
            log.error("Search failed query=\"{}\" reason={}", criteria.query(), exception.getMessage());
            throw new SearchExecutionException("Search request failed", exception);
        }
    }

    /**
     * Resolves criteria to every matching message id.
     *
     * <p>Exists so "add all N results to a case" is one call instead of the
     * client paging the archive: at the default page size, scoping a case to
     * four thousand matches was forty sequential round trips, and any change
     * to the corpus midway through produced an evidence set that matched no
     * single query.
     */
    public ResolvedIdsResponse resolveIds(SearchCriteria criteria) {
        Query query = queryBuilder.build(criteria);
        int cap = properties.getMaxResolvedIds();

        try {
            List<String> ids = indexClient.searchIds(
                    query,
                    cap,
                    properties.getResolveIdsPageSize()
            );

            boolean truncated = ids.size() >= cap;

            log.info(
                    "Resolved ids query=\"{}\" returned={} truncated={}",
                    criteria.query(),
                    ids.size(),
                    truncated
            );

            return new ResolvedIdsResponse(ids.size(), truncated, ids);
        } catch (IOException exception) {
            log.error(
                    "Id resolution failed query=\"{}\" reason={}",
                    criteria.query(),
                    exception.getMessage()
            );
            throw new SearchExecutionException("Id resolution failed", exception);
        }
    }

    /** Distinct senders in the index, most prolific first. */
    public List<CustodianResponse> custodians() {
        try {
            return indexClient.aggregateSenders(properties.getMaxCustodians()).stream()
                    .map(entry -> new CustodianResponse(entry.getKey(), entry.getValue()))
                    .toList();
        } catch (IOException exception) {
            log.error("Custodian aggregation failed reason={}", exception.getMessage());
            throw new SearchExecutionException("Custodian aggregation failed", exception);
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
                    indexingService.pendingFailureCount(),
                    indexingService.abandonedFailureCount()
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
                fragment(hit.highlight(), List.of(SUBJECT_FIELD)),
                snippet(hit.highlight(), document.getBody()),
                document.getThreadId(),
                document.getMessageTimestamp(),
                document.getAttachmentCount(),
                document.getHoldCount() > 0,
                document.getDispositionStatus()
        );
    }

    private String snippet(Map<String, List<String>> highlight, String body) {
        String fragment = fragment(highlight, SNIPPET_FIELDS);
        return fragment != null ? fragment : truncate(body);
    }

    /** The first highlighted fragment among {@code fields}, or null. */
    private String fragment(Map<String, List<String>> highlight, List<String> fields) {
        if (highlight == null) {
            return null;
        }

        for (String field : fields) {
            List<String> fragments = highlight.get(field);
            if (fragments != null && !fragments.isEmpty()) {
                return fragments.getFirst();
            }
        }

        return null;
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        int limit = properties.getSnippetLength();
        return body.length() <= limit ? body : body.substring(0, limit) + "...";
    }
}
