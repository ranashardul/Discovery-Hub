package com.stown.search.service;

import com.stown.search.api.SearchRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCriteriaTest {

    private static final int MAX_SIZE = 100;

    private static SearchCriteria criteria(SearchRequest.SearchRequestBuilder builder) {
        return SearchCriteria.of(builder.build(), MAX_SIZE);
    }

    private static String fieldOf(Throwable throwable) {
        return ((InvalidSearchRequestException) throwable).getField();
    }

    @Test
    void appliesDefaultPagination() {
        SearchCriteria criteria = SearchCriteria.ofQuery("contract", MAX_SIZE);

        assertThat(criteria.query()).isEqualTo("contract");
        assertThat(criteria.from()).isZero();
        assertThat(criteria.size()).isEqualTo(SearchCriteria.DEFAULT_SIZE);
        assertThat(criteria.communicationType()).isNull();
        assertThat(criteria.sender()).isNull();
        assertThat(criteria.threadId()).isNull();
    }

    @Test
    void defaultsToRelevanceOrdering() {
        assertThat(SearchCriteria.ofQuery("contract", MAX_SIZE).sort()).isEqualTo(SearchSort.RELEVANCE);
    }

    @Test
    void trimsBlankFiltersToNull() {
        SearchCriteria criteria = criteria(SearchRequest.builder()
                .q("  contract  ")
                .communicationType("  ")
                .sender(" alice@example.com ")
                .recipient("")
                .threadId("")
                .dispositionStatus("   ")
                .from(5)
                .size(10));

        assertThat(criteria.query()).isEqualTo("contract");
        assertThat(criteria.communicationType()).isNull();
        assertThat(criteria.sender()).isEqualTo("alice@example.com");
        assertThat(criteria.recipient()).isNull();
        assertThat(criteria.threadId()).isNull();
        assertThat(criteria.dispositionStatus()).isNull();
        assertThat(criteria.from()).isEqualTo(5);
        assertThat(criteria.size()).isEqualTo(10);
    }

    @Test
    void capsSizeAtTheConfiguredMaximum() {
        SearchCriteria criteria = criteria(SearchRequest.builder().q("contract").from(0).size(5000));

        assertThat(criteria.size()).isEqualTo(MAX_SIZE);
    }

    @Test
    void rejectsMissingQuery() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder()))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder().q("   ")))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(SearchCriteriaTest::fieldOf)
                .isEqualTo("q");
    }

    @Test
    void rejectsNegativeFrom() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder().q("contract").from(-1)))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(SearchCriteriaTest::fieldOf)
                .isEqualTo("from");
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder().q("contract").from(0).size(0)))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(SearchCriteriaTest::fieldOf)
                .isEqualTo("size");
    }

    @Test
    void parsesTheDateRange() {
        SearchCriteria criteria = criteria(SearchRequest.builder()
                .q("contract")
                .after("2026-09-01T00:00:00Z")
                .before("2026-09-08T00:00:00Z"));

        assertThat(criteria.after()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(criteria.before()).isEqualTo(Instant.parse("2026-09-08T00:00:00Z"));
        assertThat(criteria.hasDateRange()).isTrue();
    }

    @Test
    void acceptsAnOpenEndedDateRange() {
        SearchCriteria criteria = criteria(SearchRequest.builder()
                .q("contract")
                .after("2026-09-01T00:00:00Z"));

        assertThat(criteria.after()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(criteria.before()).isNull();
        assertThat(criteria.hasDateRange()).isTrue();
    }

    @Test
    void reportsNoDateRangeWhenNeitherBoundIsGiven() {
        assertThat(SearchCriteria.ofQuery("contract", MAX_SIZE).hasDateRange()).isFalse();
    }

    @Test
    void rejectsAnUnparseableDate() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder().q("contract").after("08-09-2026")))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(SearchCriteriaTest::fieldOf)
                .isEqualTo("after");
    }

    @Test
    void rejectsAnInvertedDateRange() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder()
                .q("contract")
                .after("2026-09-08T00:00:00Z")
                .before("2026-09-01T00:00:00Z")))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(SearchCriteriaTest::fieldOf)
                .isEqualTo("after");
    }

    @Test
    void acceptsEqualDateBounds() {
        SearchCriteria criteria = criteria(SearchRequest.builder()
                .q("contract")
                .after("2026-09-08T00:00:00Z")
                .before("2026-09-08T00:00:00Z"));

        assertThat(criteria.after()).isEqualTo(criteria.before());
    }

    @Test
    void parsesSortCaseInsensitively() {
        assertThat(criteria(SearchRequest.builder().q("contract").sort("NEWEST")).sort())
                .isEqualTo(SearchSort.NEWEST);
        assertThat(criteria(SearchRequest.builder().q("contract").sort(" oldest ")).sort())
                .isEqualTo(SearchSort.OLDEST);
    }

    @Test
    void rejectsAnUnknownSort() {
        assertThatThrownBy(() -> criteria(SearchRequest.builder().q("contract").sort("subject")))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(SearchCriteriaTest::fieldOf)
                .isEqualTo("sort");
    }

    @Test
    void keepsBooleanFlagsUnsetWhenAbsent() {
        SearchCriteria criteria = SearchCriteria.ofQuery("contract", MAX_SIZE);

        assertThat(criteria.onHold()).isNull();
        assertThat(criteria.hasAttachments()).isNull();
    }

    @Test
    void retainsBooleanFlagsWhenSupplied() {
        SearchCriteria criteria = criteria(SearchRequest.builder()
                .q("contract")
                .onHold(true)
                .hasAttachments(false));

        assertThat(criteria.onHold()).isTrue();
        assertThat(criteria.hasAttachments()).isFalse();
    }
}
