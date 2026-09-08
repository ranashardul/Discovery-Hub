package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import org.springframework.stereotype.Component;

@Component
public class SearchQueryBuilder {

    public static final String SUBJECT_FIELD = "subject^2";
    public static final String BODY_FIELD = "body";

    /**
     * Full text match over subject and body, narrowed by the optional keyword
     * filters.
     */
    public Query build(SearchCriteria criteria) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        bool.must(must -> must.multiMatch(multiMatch -> multiMatch
                .query(criteria.query())
                .fields(SUBJECT_FIELD, BODY_FIELD)
                .type(TextQueryType.BestFields)));

        if (criteria.communicationType() != null) {
            bool.filter(filter -> filter.term(term -> term
                    .field("communicationType")
                    .value(criteria.communicationType())));
        }

        if (criteria.sender() != null) {
            bool.filter(filter -> filter.term(term -> term
                    .field("sender.keyword")
                    .value(criteria.sender())));
        }

        if (criteria.threadId() != null) {
            bool.filter(filter -> filter.term(term -> term
                    .field("threadId")
                    .value(criteria.threadId())));
        }

        return bool.build()._toQuery();
    }
}
