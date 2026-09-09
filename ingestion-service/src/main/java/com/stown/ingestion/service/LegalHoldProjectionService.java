package com.stown.ingestion.service;

import com.mongodb.client.result.UpdateResult;
import com.stown.ingestion.domain.DispositionStatus;
import com.stown.ingestion.domain.HoldState;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.messaging.CaseHoldEvent;
import com.stown.ingestion.repository.HoldStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects legal holds owned by the case-hold service onto our own messages.
 *
 * <p>Holds are stored as a set of {@code holdIds} on each message, with
 * {@code holdCount} and {@code dispositionStatus} derived from it in the same
 * atomic update. A set rather than a counter because:
 *
 * <ul>
 *   <li>hold events are delivered at least once, and an increment would
 *       double-count on redelivery;</li>
 *   <li>a release event names only the hold, so the membership has to live
 *       here for a release to know which messages to free.</li>
 * </ul>
 *
 * <p>Overlapping holds therefore work by construction: releasing one hold only
 * clears protection when no other hold id remains.
 *
 * <p>The updates are expressed as aggregation pipelines through the driver
 * rather than the Spring criteria DSL, because deriving one field from another
 * within a single update needs {@code $set} stages that the DSL does not model
 * cleanly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalHoldProjectionService {

    private final MongoTemplate mongoTemplate;
    private final HoldStateRepository holdStateRepository;

    public void apply(CaseHoldEvent event) {
        if (event.getHoldId() == null || event.getHoldId().isBlank()) {
            log.warn("Ignoring hold event without holdId eventType={}", event.getEventType());
            return;
        }

        HoldState state = holdStateRepository.findById(event.getHoldId())
                .orElseGet(() -> HoldState.builder()
                        .holdId(event.getHoldId())
                        .caseId(event.getCaseId())
                        .appliedEventIds(new ArrayList<>())
                        .firstSeenAt(Instant.now())
                        .build());

        if (alreadyApplied(state, event)) {
            log.debug(
                    "Ignoring duplicate hold event holdId={} eventId={} eventType={}",
                    event.getHoldId(),
                    event.getEventId(),
                    event.getEventType()
            );
            return;
        }

        if (event.isHoldCreated() && "RELEASED".equalsIgnoreCase(state.getStatus())) {
            // A replayed create for a hold already seen released would
            // otherwise re-protect messages that are free to dispose.
            log.info(
                    "Ignoring stale HOLD_CREATED for released holdId={} eventId={}",
                    event.getHoldId(),
                    event.getEventId()
            );
            recordEvent(state, event, state.getMessagesAffected());
            return;
        }

        long affected = event.isHoldCreated() ? place(event) : release(event);

        recordEvent(state, event, affected);
    }

    private long place(CaseHoldEvent event) {
        Bson filter = event.isCriteriaScope()
                ? criteriaFilter(event)
                : communicationFilter(event);

        if (filter == null) {
            log.warn(
                    "HOLD_CREATED holdId={} matched nothing: no communicationIds and no criteria",
                    event.getHoldId()
            );
            return 0;
        }

        // holdIds = setUnion(holdIds, [holdId]) -> then derive count and status.
        Document addHold = new Document("$set", new Document("holdIds",
                new Document("$setUnion", List.of(
                        new Document("$ifNull", List.of("$holdIds", List.of())),
                        List.of(event.getHoldId())
                ))));

        long modified = applyPipeline(filter, List.of(addHold, deriveStage()));

        log.info(
                "Applied hold holdId={} caseId={} scope={} messagesAffected={}",
                event.getHoldId(),
                event.getCaseId(),
                event.getScope(),
                modified
        );

        return modified;
    }

    private long release(CaseHoldEvent event) {
        Document removeHold = new Document("$set", new Document("holdIds",
                new Document("$setDifference", List.of(
                        new Document("$ifNull", List.of("$holdIds", List.of())),
                        List.of(event.getHoldId())
                ))));

        long modified = applyPipeline(
                new Document("holdIds", event.getHoldId()),
                List.of(removeHold, deriveStage())
        );

        log.info(
                "Released hold holdId={} caseId={} messagesAffected={}",
                event.getHoldId(),
                event.getCaseId(),
                modified
        );

        return modified;
    }

    /**
     * Derives {@code holdCount} and {@code dispositionStatus} from the set in
     * the same update, so the three can never drift apart.
     */
    private Document deriveStage() {
        return new Document("$set", new Document()
                .append("holdCount", new Document("$size",
                        new Document("$ifNull", List.of("$holdIds", List.of()))))
                .append("dispositionStatus", new Document("$cond", List.of(
                        new Document("$gt", List.of(
                                new Document("$size",
                                        new Document("$ifNull", List.of("$holdIds", List.of()))),
                                0
                        )),
                        DispositionStatus.ON_HOLD,
                        DispositionStatus.ACTIVE
                ))));
    }

    private long applyPipeline(Bson filter, List<? extends Bson> pipeline) {
        UpdateResult result = mongoTemplate
                .getCollection(mongoTemplate.getCollectionName(MessageDocument.class))
                .updateMany(filter, new ArrayList<>(pipeline));

        return result.getModifiedCount();
    }

    private Bson communicationFilter(CaseHoldEvent event) {
        List<String> ids = event.getCommunicationIds();

        if (ids == null || ids.isEmpty()) {
            return null;
        }

        return new Document("_id", new Document("$in", ids));
    }

    /**
     * Matches the hold rule against our own store, which is what the case-hold
     * service expects the message owner to do for CRITERIA scope.
     */
    private Bson criteriaFilter(CaseHoldEvent event) {
        List<Criteria> conditions = new ArrayList<>();

        List<String> participants = event.getCriteriaParticipants();
        if (participants != null && !participants.isEmpty()) {
            conditions.add(new Criteria().orOperator(
                    Criteria.where("sender").in(participants),
                    Criteria.where("recipients").in(participants)
            ));
        }

        List<String> types = event.getCriteriaCommunicationTypes();
        if (types != null && !types.isEmpty()) {
            conditions.add(Criteria.where("communicationType").in(types));
        }

        if (event.getCriteriaDateFrom() != null || event.getCriteriaDateTo() != null) {
            Criteria range = Criteria.where("messageTimestamp");
            if (event.getCriteriaDateFrom() != null) {
                range = range.gte(event.getCriteriaDateFrom());
            }
            if (event.getCriteriaDateTo() != null) {
                range = range.lte(event.getCriteriaDateTo());
            }
            conditions.add(range);
        }

        if (conditions.isEmpty()) {
            return null;
        }

        return Query.query(new Criteria().andOperator(conditions)).getQueryObject();
    }

    private boolean alreadyApplied(HoldState state, CaseHoldEvent event) {
        return event.getEventId() != null
                && state.getAppliedEventIds() != null
                && state.getAppliedEventIds().contains(event.getEventId());
    }

    private void recordEvent(HoldState state, CaseHoldEvent event, long affected) {
        if (state.getAppliedEventIds() == null) {
            state.setAppliedEventIds(new ArrayList<>());
        }
        if (event.getEventId() != null) {
            state.getAppliedEventIds().add(event.getEventId());
        }

        state.setCaseId(event.getCaseId() != null ? event.getCaseId() : state.getCaseId());
        state.setScope(event.getScope() != null ? event.getScope() : state.getScope());
        state.setStatus(event.isHoldReleased() ? "RELEASED" : "ACTIVE");
        state.setMessagesAffected(affected);
        state.setLastEventAt(event.getOccurredAt() == null ? Instant.now() : event.getOccurredAt());

        holdStateRepository.save(state);
    }
}
