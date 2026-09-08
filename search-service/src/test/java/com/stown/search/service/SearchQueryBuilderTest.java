package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryBuilderTest {

    private final SearchQueryBuilder builder = new SearchQueryBuilder();

    @Test
    void buildsMultiMatchOverSubjectAndBody() {
        Query query = builder.build(
                SearchCriteria.of("merger agreement", null, null, null, null, null, 100)
        );

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
        Query query = builder.build(SearchCriteria.of(
                "merger", "EMAIL", "alice@example.com", "thread-1", 0, 20, 100
        ));

        List<Query> filters = query.bool().filter();
        assertThat(filters).hasSize(3);
        assertThat(filters).extracting(filter -> filter.term().field())
                .containsExactly("communicationType", "sender.keyword", "threadId");
        assertThat(filters).extracting(filter -> filter.term().value().stringValue())
                .containsExactly("EMAIL", "alice@example.com", "thread-1");
    }

    @Test
    void omitsFiltersThatAreNotSupplied() {
        Query query = builder.build(SearchCriteria.of(
                "merger", null, "alice@example.com", null, 0, 20, 100
        ));

        assertThat(query.bool().filter()).hasSize(1);
        assertThat(query.bool().filter().getFirst().term().field()).isEqualTo("sender.keyword");
    }
}
