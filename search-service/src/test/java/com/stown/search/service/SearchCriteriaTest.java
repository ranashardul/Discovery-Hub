package com.stown.search.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchCriteriaTest {

    private static final int MAX_SIZE = 100;

    @Test
    void appliesDefaultPagination() {
        SearchCriteria criteria = SearchCriteria.of("contract", null, null, null, null, null, MAX_SIZE);

        assertThat(criteria.query()).isEqualTo("contract");
        assertThat(criteria.from()).isZero();
        assertThat(criteria.size()).isEqualTo(SearchCriteria.DEFAULT_SIZE);
        assertThat(criteria.communicationType()).isNull();
        assertThat(criteria.sender()).isNull();
        assertThat(criteria.threadId()).isNull();
    }

    @Test
    void trimsBlankFiltersToNull() {
        SearchCriteria criteria = SearchCriteria.of(
                "  contract  ", "  ", " alice@example.com ", "", 5, 10, MAX_SIZE
        );

        assertThat(criteria.query()).isEqualTo("contract");
        assertThat(criteria.communicationType()).isNull();
        assertThat(criteria.sender()).isEqualTo("alice@example.com");
        assertThat(criteria.threadId()).isNull();
        assertThat(criteria.from()).isEqualTo(5);
        assertThat(criteria.size()).isEqualTo(10);
    }

    @Test
    void capsSizeAtTheConfiguredMaximum() {
        SearchCriteria criteria = SearchCriteria.of("contract", null, null, null, 0, 5000, MAX_SIZE);

        assertThat(criteria.size()).isEqualTo(MAX_SIZE);
    }

    @Test
    void rejectsMissingQuery() {
        assertThatThrownBy(() -> SearchCriteria.of(null, null, null, null, null, null, MAX_SIZE))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> SearchCriteria.of("   ", null, null, null, null, null, MAX_SIZE))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(exception -> ((InvalidSearchRequestException) exception).getField())
                .isEqualTo("q");
    }

    @Test
    void rejectsNegativeFrom() {
        assertThatThrownBy(() -> SearchCriteria.of("contract", null, null, null, -1, null, MAX_SIZE))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(exception -> ((InvalidSearchRequestException) exception).getField())
                .isEqualTo("from");
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> SearchCriteria.of("contract", null, null, null, 0, 0, MAX_SIZE))
                .isInstanceOf(InvalidSearchRequestException.class)
                .extracting(exception -> ((InvalidSearchRequestException) exception).getField())
                .isEqualTo("size");
    }
}
