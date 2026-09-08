package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SearchQueryBuilder {

    public static final String SUBJECT_FIELD = "subject^2";
    public static final String BODY_FIELD = "body";

    static final String TIMESTAMP_FIELD = "messageTimestamp";

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

        term(bool, "communicationType", criteria.communicationType());
        term(bool, "sender.keyword", criteria.sender());
        term(bool, "recipients.keyword", criteria.recipient());
        term(bool, "threadId", criteria.threadId());
        term(bool, "dispositionStatus", criteria.dispositionStatus());

        // holdCount is maintained by the case/hold service; anything above zero
        // means the message is under at least one active legal hold.
        countFilter(bool, "holdCount", criteria.onHold());
        countFilter(bool, "attachmentCount", criteria.hasAttachments());

        if (criteria.hasDateRange()) {
            bool.filter(filter -> filter.range(range -> range.date(date -> {
                date.field(TIMESTAMP_FIELD);
                if (criteria.after() != null) {
                    date.gte(criteria.after().toString());
                }
                if (criteria.before() != null) {
                    date.lte(criteria.before().toString());
                }
                return date;
            })));
        }

        return bool.build()._toQuery();
    }

    /**
     * Relevance ordering is Elasticsearch's default and needs no explicit sort;
     * the chronological orderings break ties on {@code messageId} so that deep
     * pagination stays stable.
     */
    public List<SortOptions> sort(SearchCriteria criteria) {
        return switch (criteria.sort()) {
            case RELEVANCE -> List.of();
            case NEWEST -> timestampSort(SortOrder.Desc);
            case OLDEST -> timestampSort(SortOrder.Asc);
        };
    }

    private List<SortOptions> timestampSort(SortOrder order) {
        return List.of(
                SortOptions.of(sort -> sort.field(field -> field.field(TIMESTAMP_FIELD).order(order))),
                SortOptions.of(sort -> sort.field(field -> field.field("messageId").order(SortOrder.Asc)))
        );
    }

    private void term(BoolQuery.Builder bool, String field, String value) {
        if (value != null) {
            bool.filter(filter -> filter.term(term -> term.field(field).value(value)));
        }
    }

    /**
     * Translates a present/absent flag into a filter over a non-negative count
     * field: {@code true} means at least one, {@code false} means exactly none.
     */
    private void countFilter(BoolQuery.Builder bool, String field, Boolean expected) {
        if (expected == null) {
            return;
        }

        if (expected) {
            bool.filter(filter -> filter.range(range -> range.number(number -> number
                    .field(field)
                    .gte(1d))));
        } else {
            bool.filter(filter -> filter.term(term -> term.field(field).value(0)));
        }
    }
}
