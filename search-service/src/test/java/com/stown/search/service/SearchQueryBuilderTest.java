package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.stown.search.api.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryBuilderTest {

    private static final int MAX_SIZE = 100;

    private final SearchQueryBuilder builder = new SearchQueryBuilder();

    private SearchCriteria criteria(SearchRequest.SearchRequestBuilder request) {
        return SearchCriteria.of(request.build(), MAX_SIZE);
    }

    @Test
    void buildsMultiMatchOverSubjectAndBody() {
        Query query = builder.build(SearchCriteria.ofQuery("merger agreement", MAX_SIZE));

        BoolQuery bool = query.bool();
        assertThat(bool.filter()).isEmpty();
        assertThat(bool.must()).hasSize(1);

        MultiMatchQuery multiMatch = bool.must().getFirst().multiMatch();
        assertThat(multiMatch.query()).isEqualTo("merger agreement");
        assertThat(multiMatch.fields()).containsExactly(
                SearchQueryBuilder.SUBJECT_FIELD,
                SearchQueryBuilder.BODY_FIELD
        );
    }

    @Test
    void addsKeywordFiltersWhenPresent() {
        Query query = builder.build(criteria(SearchRequest.builder()
                .q("merger")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipient("bob@example.com")
                .threadId("thread-1")
                .dispositionStatus("ON_HOLD")
                .holdId("hold-demo-1")));

        List<Query> filters = query.bool().filter();
        assertThat(filters).extracting(filter -> filter.term().field())
                .containsExactly(
                        "communicationType",
                        "sender.keyword",
                        "recipients.keyword",
                        "threadId",
                        "dispositionStatus",
                        "holdIds"
                );
        assertThat(filters).extracting(filter -> filter.term().value().stringValue())
                .containsExactly(
                        "EMAIL", "alice@example.com", "bob@example.com",
                        "thread-1", "ON_HOLD", "hold-demo-1"
                );
    }

    @Test
    void filtersByASingleLegalHoldId() {
        // holdIds is an array in the document, so a term match means
        // "this message is under that hold" rather than "equals the whole set".
        Query query = builder.build(criteria(SearchRequest.builder()
                .q("merger")
                .holdId("hold-demo-1")));

        List<Query> filters = query.bool().filter();
        assertThat(filters).hasSize(1);
        assertThat(filters.getFirst().term().field()).isEqualTo("holdIds");
        assertThat(filters.getFirst().term().value().stringValue()).isEqualTo("hold-demo-1");
    }

    @Test
    void omitsFiltersThatAreNotSupplied() {
        Query query = builder.build(criteria(SearchRequest.builder()
                .q("merger")
                .sender("alice@example.com")));

        assertThat(query.bool().filter()).hasSize(1);
        assertThat(query.bool().filter().getFirst().term().field()).isEqualTo("sender.keyword");
    }

    /**
     * A hold's {@code participants} rule means "sender or any recipient", so
     * it has to be one OR clause. Combining the separate sender and recipient
     * filters would AND them and match only someone who wrote to themselves.
     */
    @Test
    void matchesAParticipantOnEitherSideOfTheConversation() {
        Query query = builder.build(criteria(
                SearchRequest.builder().participants(List.of("alice@example.com"))
        ));

        Query participantClause = query.bool().filter().getFirst();
        BoolQuery inner = participantClause.bool();

        assertThat(inner.should()).hasSize(2);
        assertThat(inner.minimumShouldMatch()).isEqualTo("1");
        assertThat(inner.should()).extracting(should -> should.terms().field())
                .containsExactly("sender.keyword", "recipients.keyword");
    }

    /** Several participants are an OR, so they stay in one clause. */
    @Test
    void treatsSeveralParticipantsAsOneOrClause() {
        Query query = builder.build(criteria(
                SearchRequest.builder().participants(List.of("alice@example.com", "bob@example.com"))
        ));

        assertThat(query.bool().filter()).hasSize(1);

        List<String> senderTerms = query.bool().filter().getFirst().bool().should().getFirst()
                .terms().terms().value().stream()
                .map(value -> value.stringValue())
                .toList();

        assertThat(senderTerms).containsExactly("alice@example.com", "bob@example.com");
    }

    @Test
    void ignoresBlankAndDuplicateParticipants() {
        Query query = builder.build(criteria(SearchRequest.builder()
                .q("merger")
                .participants(List.of("alice@example.com", "  ", "alice@example.com"))));

        List<String> senderTerms = query.bool().filter().getFirst().bool().should().getFirst()
                .terms().terms().value().stream()
                .map(value -> value.stringValue())
                .toList();

        assertThat(senderTerms).containsExactly("alice@example.com");
    }

    /** A participant filter alone is enough to make a request valid. */
    @Test
    void acceptsAParticipantFilterWithNoQueryText() {
        Query query = builder.build(criteria(
                SearchRequest.builder().participants(List.of("alice@example.com"))
        ));

        assertThat(query.bool().must()).hasSize(1);
        assertThat(query.bool().must().getFirst().isMatchAll()).isTrue();
    }

    @Test
    void filtersOnMessagesUnderLegalHold() {
        Query query = builder.build(criteria(SearchRequest.builder().q("merger").onHold(true)));

        List<Query> filters = query.bool().filter();
        assertThat(filters).hasSize(1);
        assertThat(filters.getFirst().range().number().field()).isEqualTo("holdCount");
        assertThat(filters.getFirst().range().number().gte()).isEqualTo(1d);
    }

    @Test
    void filtersOnMessagesNotUnderLegalHold() {
        Query query = builder.build(criteria(SearchRequest.builder().q("merger").onHold(false)));

        List<Query> filters = query.bool().filter();
        assertThat(filters).hasSize(1);
        assertThat(filters.getFirst().term().field()).isEqualTo("holdCount");
        assertThat(filters.getFirst().term().value().longValue()).isZero();
    }

    @Test
    void filtersOnAttachmentPresence() {
        Query withAttachments = builder.build(
                criteria(SearchRequest.builder().q("merger").hasAttachments(true))
        );
        assertThat(withAttachments.bool().filter().getFirst().range().number().field())
                .isEqualTo("attachmentCount");

        Query withoutAttachments = builder.build(
                criteria(SearchRequest.builder().q("merger").hasAttachments(false))
        );
        assertThat(withoutAttachments.bool().filter().getFirst().term().field())
                .isEqualTo("attachmentCount");
    }

    @Test
    void addsADateRangeFilterWithBothBounds() {
        Query query = builder.build(criteria(SearchRequest.builder()
                .q("merger")
                .after("2026-09-01T00:00:00Z")
                .before("2026-09-08T00:00:00Z")));

        List<Query> filters = query.bool().filter();
        assertThat(filters).hasSize(1);
        assertThat(filters.getFirst().range().date().field()).isEqualTo(SearchQueryBuilder.TIMESTAMP_FIELD);
        assertThat(filters.getFirst().range().date().gte()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(filters.getFirst().range().date().lte()).isEqualTo("2026-09-08T00:00:00Z");
    }

    @Test
    void addsAnOpenEndedDateRangeFilter() {
        Query query = builder.build(criteria(SearchRequest.builder()
                .q("merger")
                .after("2026-09-01T00:00:00Z")));

        assertThat(query.bool().filter().getFirst().range().date().gte()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(query.bool().filter().getFirst().range().date().lte()).isNull();
    }

    @Test
    void omitsTheDateRangeFilterWhenUnbounded() {
        Query query = builder.build(SearchCriteria.ofQuery("merger", MAX_SIZE));

        assertThat(query.bool().filter()).isEmpty();
    }

    @Test
    void matchesEverythingWhenOnlyFiltersAreSupplied() {
        Query query = builder.build(criteria(SearchRequest.builder().threadId("thread-1")));

        // Without a positive clause a bool query returns nothing, so the
        // filter-only case needs match_all rather than an empty must.
        assertThat(query.bool().must()).hasSize(1);
        assertThat(query.bool().must().getFirst().isMatchAll()).isTrue();
        assertThat(query.bool().filter()).hasSize(1);
        assertThat(query.bool().filter().getFirst().term().field()).isEqualTo("threadId");
    }

    @Test
    void sortsFilterOnlyResultsByTimestampInsteadOfRelevance() {
        // Every document scores identically without text, so relevance order
        // is arbitrary and unstable between requests.
        List<SortOptions> sort = builder.sort(criteria(SearchRequest.builder().threadId("thread-1")));

        assertThat(sort).hasSize(2);
        assertThat(sort.getFirst().field().field()).isEqualTo(SearchQueryBuilder.TIMESTAMP_FIELD);
        assertThat(sort.getFirst().field().order()).isEqualTo(SortOrder.Desc);
    }

    @Test
    void leavesOrderingToElasticsearchForRelevance() {
        assertThat(builder.sort(SearchCriteria.ofQuery("merger", MAX_SIZE))).isEmpty();
    }

    @Test
    void sortsNewestFirstWithAStableTieBreak() {
        List<SortOptions> sort = builder.sort(criteria(SearchRequest.builder().q("merger").sort("newest")));

        assertThat(sort).hasSize(2);
        assertThat(sort.getFirst().field().field()).isEqualTo(SearchQueryBuilder.TIMESTAMP_FIELD);
        assertThat(sort.getFirst().field().order()).isEqualTo(SortOrder.Desc);
        assertThat(sort.getLast().field().field()).isEqualTo(SearchQueryBuilder.ID_TIEBREAK_FIELD);
        assertThat(sort.getLast().field().order()).isEqualTo(SortOrder.Asc);
    }

    /**
     * The tiebreaker must be a field the index mapping declares as
     * {@code keyword}, because only those carry doc values and are sortable.
     * {@code MessageIndexClient.ensureIndex} maps {@code messageId} that way.
     *
     * <p>This is asserted for both chronological orders because a request that
     * sorts on an analysed field does not degrade — Elasticsearch fails it
     * outright with "all shards failed", which takes out every chronological
     * search and, since they fall back to this ordering, every filter-only
     * search too.
     */
    @Test
    void breaksTiesOnAFieldTheMappingDeclaresAsKeyword() {
        for (String order : List.of("newest", "oldest")) {
            List<SortOptions> sort = builder.sort(
                    criteria(SearchRequest.builder().q("merger").sort(order))
            );

            assertThat(sort.getLast().field().field())
                    .as("tiebreaker for sort=%s must be a keyword-mapped field", order)
                    .isEqualTo("messageId");
        }
    }

    /**
     * A filter-only request has no text to score, so it falls back to the
     * chronological ordering. That path is what the dashboard's archive
     * composition counts and the thread view both use.
     */
    @Test
    void fallsBackToAChronologicalSortWhenThereIsNoQueryText() {
        List<SortOptions> sort = builder.sort(
                criteria(SearchRequest.builder().communicationType("EMAIL"))
        );

        assertThat(sort).hasSize(2);
        assertThat(sort.getFirst().field().field()).isEqualTo(SearchQueryBuilder.TIMESTAMP_FIELD);
        assertThat(sort.getLast().field().field()).isEqualTo(SearchQueryBuilder.ID_TIEBREAK_FIELD);
    }

    @Test
    void sortsOldestFirst() {
        List<SortOptions> sort = builder.sort(criteria(SearchRequest.builder().q("merger").sort("oldest")));

        assertThat(sort.getFirst().field().field()).isEqualTo(SearchQueryBuilder.TIMESTAMP_FIELD);
        assertThat(sort.getFirst().field().order()).isEqualTo(SortOrder.Asc);
    }
}
