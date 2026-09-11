package com.stown.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
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

    /**
     * Participants are part of the full-text surface, not only of the exact
     * filters: "find everything about Imogen Reddy" is a question a reviewer
     * asks before they know the address to filter on, and without these the
     * only hits are incidental mentions in a body.
     *
     * <p>Both are the analysed {@code text} field, not the {@code .keyword}
     * sub-field the filters use, so a partial name matches. They are boosted
     * below {@code subject} because a name in the subject line is a stronger
     * signal than the same name in a distribution list.
     *
     * <p>Terms stay OR'd, as everywhere else in this query. Pasting a whole
     * address here is therefore still a poor way to find someone — every
     * custodian shares a mail domain, so the domain tokens match the entire
     * corpus and only the ranking saves it. That is what the {@code sender},
     * {@code recipient} and {@code participant} filters are for. Requiring
     * every term instead would fix that one input and break ordinary ones:
     * against the seeded corpus {@code operator=AND} takes "preservation
     * notice" from 1,019 hits to zero.
     */
    public static final String SENDER_FIELD = "sender^1.5";
    public static final String RECIPIENTS_FIELD = "recipients";

    static final String TIMESTAMP_FIELD = "messageTimestamp";

    /**
     * Tiebreaker for the chronological sorts.
     *
     * <p>{@code messageId} is mapped as {@code keyword} by
     * {@code MessageIndexClient.ensureIndex}, so it has doc values and is
     * sortable. This only holds on an index created from that mapping: on one
     * Elasticsearch auto-created with a dynamic mapping the field is analysed
     * {@code text}, sorting on it needs fielddata, and the request fails with
     * {@code all shards failed} rather than degrading — which takes out every
     * chronological search and every filter-only search, since those fall
     * back to this ordering.
     */
    static final String ID_TIEBREAK_FIELD = "messageId";

    /**
     * Full text match over subject, body and participants, narrowed by the
     * optional keyword filters.
     */
    public Query build(SearchCriteria criteria) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (criteria.hasQuery()) {
            bool.must(must -> must.multiMatch(multiMatch -> multiMatch
                    .query(criteria.query())
                    .fields(SUBJECT_FIELD, BODY_FIELD, SENDER_FIELD, RECIPIENTS_FIELD)
                    .type(TextQueryType.BestFields)));
        } else {
            // Filter-only request: match everything and let the filters narrow
            // it. Without this the bool query has no positive clause and
            // returns nothing.
            bool.must(must -> must.matchAll(matchAll -> matchAll));
        }

        term(bool, "communicationType", criteria.communicationType());
        term(bool, "sender.keyword", criteria.sender());
        term(bool, "recipients.keyword", criteria.recipient());
        participants(bool, criteria.participants());
        term(bool, "threadId", criteria.threadId());
        term(bool, "dispositionStatus", criteria.dispositionStatus());
        // holdIds is a keyword array, so a term match means "this message is
        // under that specific hold" - the question a reviewer working a single
        // legal hold actually asks.
        term(bool, "holdIds", criteria.holdId());

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
            // Relevance is meaningless without text to score against: every
            // document matches equally, so results come back in an arbitrary
            // order that shifts between requests. Fall back to newest first.
            case RELEVANCE -> criteria.hasQuery() ? List.of() : timestampSort(SortOrder.Desc);
            case NEWEST -> timestampSort(SortOrder.Desc);
            case OLDEST -> timestampSort(SortOrder.Asc);
        };
    }

    private List<SortOptions> timestampSort(SortOrder order) {
        return List.of(
                SortOptions.of(sort -> sort.field(field -> field.field(TIMESTAMP_FIELD).order(order))),
                SortOptions.of(sort -> sort.field(field -> field
                        .field(ID_TIEBREAK_FIELD)
                        .order(SortOrder.Asc)))
        );
    }

    /**
     * Matches any of the named identities on either side of a conversation:
     * sender, or any recipient.
     *
     * <p>Exists because that is what a legal hold's {@code participants} rule
     * means, and it cannot be expressed by combining the {@code sender} and
     * {@code recipient} filters — those are separate {@code filter} clauses
     * and therefore an AND, which would only match someone who wrote to
     * themselves.
     *
     * <p>Several participants are an OR, so the whole rule is one clause
     * rather than one per name. Mirrors
     * {@code LegalHoldProjectionService.criteriaFilter} in ingestion-service,
     * which resolves the same rule against MongoDB when the hold is actually
     * applied: keeping the two in step is what lets the hold scope preview
     * delegate here instead of reimplementing the predicate and disagreeing
     * with the hold it previewed.
     */
    private void participants(BoolQuery.Builder bool, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        List<FieldValue> identities = values.stream().map(FieldValue::of).toList();

        bool.filter(filter -> filter.bool(inner -> inner
                .should(should -> should.terms(terms -> terms
                        .field("sender.keyword")
                        .terms(set -> set.value(identities))))
                .should(should -> should.terms(terms -> terms
                        .field("recipients.keyword")
                        .terms(set -> set.value(identities))))
                .minimumShouldMatch("1")));
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
