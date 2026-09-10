package com.stown.exportaudit.repository;

import com.stown.exportaudit.domain.AuditEventDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Filtered, paged reads over the audit trail.
 *
 * <p>Separate from the derived-query {@link AuditEventRepository} because the
 * filters are all optional and combine freely: expressing that as Spring Data
 * method names would need one method per combination. Read-only by design —
 * the audit trail is append-only, and nothing here can write.
 */
@Repository
@RequiredArgsConstructor
public class AuditEventQueryRepository {

    private final MongoTemplate mongoTemplate;

    public record AuditFilter(
            String caseId,
            String actor,
            String action,
            String targetType,
            String targetId,
            Instant from,
            Instant to
    ) {
    }

    /**
     * Newest first, which is the order a reviewer reads a chain of custody in.
     * {@code eventId} breaks ties so paging cannot repeat or skip an entry
     * when several events share a timestamp — which they do, because a single
     * operation can emit more than one.
     */
    public List<AuditEventDocument> find(AuditFilter filter, int page, int size) {
        Query query = new Query(toCriteria(filter))
                .with(Sort.by(Sort.Direction.DESC, "timestamp").and(Sort.by("eventId")))
                .skip((long) page * size)
                .limit(size);

        return mongoTemplate.find(query, AuditEventDocument.class);
    }

    public long count(AuditFilter filter) {
        return mongoTemplate.count(new Query(toCriteria(filter)), AuditEventDocument.class);
    }

    /** Distinct actors, for a filter dropdown. */
    public List<String> distinctActors() {
        return mongoTemplate.findDistinct(
                new Query(),
                "actor",
                AuditEventDocument.class,
                String.class
        );
    }

    private Criteria toCriteria(AuditFilter filter) {
        List<Criteria> conditions = new ArrayList<>();

        addIfPresent(conditions, "caseId", filter.caseId());
        addIfPresent(conditions, "actor", filter.actor());
        addIfPresent(conditions, "action", filter.action());
        addIfPresent(conditions, "targetType", filter.targetType());
        addIfPresent(conditions, "targetId", filter.targetId());

        if (filter.from() != null || filter.to() != null) {
            Criteria range = Criteria.where("timestamp");
            if (filter.from() != null) {
                range = range.gte(filter.from());
            }
            if (filter.to() != null) {
                range = range.lte(filter.to());
            }
            conditions.add(range);
        }

        return conditions.isEmpty()
                ? new Criteria()
                : new Criteria().andOperator(conditions.toArray(new Criteria[0]));
    }

    private void addIfPresent(List<Criteria> conditions, String field, String value) {
        if (value != null && !value.isBlank()) {
            conditions.add(Criteria.where(field).is(value.trim()));
        }
    }
}
