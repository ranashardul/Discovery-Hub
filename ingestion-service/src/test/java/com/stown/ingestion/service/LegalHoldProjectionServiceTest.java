package com.stown.ingestion.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import com.stown.ingestion.domain.HoldState;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.messaging.CaseHoldEvent;
import com.stown.ingestion.repository.HoldStateRepository;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the projection of legal holds owned by the case-hold service.
 *
 * <p>These assert the update actually sent to MongoDB, because the safety
 * properties live in that pipeline: the set semantics that make redelivery
 * harmless, and deriving {@code holdCount} in the same operation so it cannot
 * drift from {@code holdIds}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegalHoldProjectionServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private HoldStateRepository holdStateRepository;

    @Mock
    private MongoCollection<Document> collection;

    @Mock
    private UpdateResult updateResult;

    private LegalHoldProjectionService service;

    @BeforeEach
    void setUp() {
        service = new LegalHoldProjectionService(mongoTemplate, holdStateRepository);

        when(mongoTemplate.getCollectionName(MessageDocument.class)).thenReturn("messages");
        when(mongoTemplate.getCollection("messages")).thenReturn(collection);
        when(collection.updateMany(any(Bson.class), anyList())).thenReturn(updateResult);
        when(updateResult.getModifiedCount()).thenReturn(1L);
        when(holdStateRepository.findById(any())).thenReturn(Optional.empty());
        when(holdStateRepository.save(any(HoldState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------ validation

    @Test
    void ignoresAnEventWithoutAHoldId() {
        service.apply(CaseHoldEvent.builder()
                .eventType(CaseHoldEvent.HOLD_CREATED)
                .eventId("e1")
                .build());

        verify(collection, never()).updateMany(any(Bson.class), anyList());
        verify(holdStateRepository, never()).save(any());
    }

    // ------------------------------------------------------- placing a hold

    @Test
    void addsTheHoldIdAsASetUnionForCommunicationScope() {
        service.apply(created("hold-1", List.of("msg-1", "msg-2")));

        List<Bson> pipeline = capturePipeline();

        // $setUnion, not $push: a redelivered event must not add the id twice.
        String addStage = pipeline.getFirst().toString();
        assertThat(addStage).contains("$setUnion");
        assertThat(addStage).contains("holdIds");
        assertThat(addStage).contains("hold-1");
    }

    @Test
    void derivesHoldCountAndStatusInTheSameUpdate() {
        service.apply(created("hold-2", List.of("msg-1")));

        List<Bson> pipeline = capturePipeline();

        // Two stages: mutate the set, then derive from it. Deriving in the
        // same update is what stops holdCount drifting from holdIds.
        assertThat(pipeline).hasSize(2);

        String deriveStage = pipeline.get(1).toString();
        assertThat(deriveStage).contains("holdCount");
        assertThat(deriveStage).contains("$size");
        assertThat(deriveStage).contains("dispositionStatus");
        assertThat(deriveStage).contains("ON_HOLD");
        assertThat(deriveStage).contains("ACTIVE");
    }

    @Test
    void filtersByMessageIdForCommunicationScope() {
        service.apply(created("hold-3", List.of("msg-7", "msg-8")));

        Bson filter = captureFilter();

        assertThat(filter.toString()).contains("_id");
        assertThat(filter.toString()).contains("msg-7");
        assertThat(filter.toString()).contains("msg-8");
    }

    @Test
    void doesNothingWhenACommunicationScopeHoldNamesNoMessages() {
        service.apply(created("hold-4", List.of()));

        verify(collection, never()).updateMany(any(Bson.class), anyList());
        // The hold is still recorded, so a later release is understood.
        verify(holdStateRepository).save(any(HoldState.class));
    }

    // -------------------------------------------------------- criteria scope

    @Test
    void matchesParticipantsAgainstSenderAndRecipientsForCriteriaScope() {
        CaseHoldEvent event = CaseHoldEvent.builder()
                .eventType(CaseHoldEvent.HOLD_CREATED)
                .eventId(UUID.randomUUID().toString())
                .holdId("hold-criteria-1")
                .scope(CaseHoldEvent.SCOPE_CRITERIA)
                .criteriaParticipants(List.of("alice@example-bank.test"))
                .criteriaCommunicationTypes(List.of("EMAIL"))
                .criteriaDateFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .criteriaDateTo(Instant.parse("2026-12-31T23:59:59Z"))
                .build();

        service.apply(event);

        String filter = captureFilter().toString();

        // A participant may be the sender or a recipient, so both are matched.
        assertThat(filter).contains("sender");
        assertThat(filter).contains("recipients");
        assertThat(filter).contains("communicationType");
        assertThat(filter).contains("messageTimestamp");
    }

    @Test
    void doesNothingWhenACriteriaHoldCarriesNoRules() {
        service.apply(CaseHoldEvent.builder()
                .eventType(CaseHoldEvent.HOLD_CREATED)
                .eventId("e-empty")
                .holdId("hold-empty")
                .scope(CaseHoldEvent.SCOPE_CRITERIA)
                .build());

        // An unbounded criteria hold would otherwise freeze the whole corpus.
        verify(collection, never()).updateMany(any(Bson.class), anyList());
    }

    // ------------------------------------------------------ releasing a hold

    @Test
    void removesTheHoldIdWithSetDifferenceOnRelease() {
        service.apply(released("hold-5"));

        List<Bson> pipeline = capturePipeline();

        assertThat(pipeline.getFirst().toString()).contains("$setDifference");
        assertThat(pipeline.get(1).toString()).contains("holdCount");
    }

    @Test
    void releaseTargetsOnlyMessagesCarryingThatHold() {
        service.apply(released("hold-6"));

        String filter = captureFilter().toString();

        // The release event names no messages, so membership recorded on the
        // documents is the only way to find them.
        assertThat(filter).contains("holdIds");
        assertThat(filter).contains("hold-6");
    }

    @Test
    void recordsTheHoldAsReleased() {
        service.apply(released("hold-7"));

        ArgumentCaptor<HoldState> captor = ArgumentCaptor.forClass(HoldState.class);
        verify(holdStateRepository).save(captor.capture());

        assertThat(captor.getValue().getStatus()).isEqualTo("RELEASED");
    }

    // ---------------------------------------------------------- idempotency

    @Test
    void ignoresAnEventIdAlreadyApplied() {
        String eventId = UUID.randomUUID().toString();

        HoldState existing = HoldState.builder()
                .holdId("hold-8")
                .status("ACTIVE")
                .appliedEventIds(new ArrayList<>(List.of(eventId)))
                .build();

        when(holdStateRepository.findById("hold-8")).thenReturn(Optional.of(existing));

        CaseHoldEvent replay = CaseHoldEvent.builder()
                .eventType(CaseHoldEvent.HOLD_CREATED)
                .eventId(eventId)
                .holdId("hold-8")
                .scope(CaseHoldEvent.SCOPE_COMMUNICATION)
                .communicationIds(List.of("msg-1"))
                .build();

        service.apply(replay);

        // Delivery is at-least-once, so a duplicate must be a no-op.
        verify(collection, never()).updateMany(any(Bson.class), anyList());
    }

    @Test
    void appliesTheSameHoldTwiceWhenTheEventIdsDiffer() {
        HoldState existing = HoldState.builder()
                .holdId("hold-9")
                .status("ACTIVE")
                .appliedEventIds(new ArrayList<>(List.of("first-event")))
                .build();

        when(holdStateRepository.findById("hold-9")).thenReturn(Optional.of(existing));

        service.apply(created("hold-9", List.of("msg-1")));

        // A genuinely new event is applied; the set union keeps it harmless.
        verify(collection).updateMany(any(Bson.class), anyList());
    }

    @Test
    void ignoresAStaleCreateForAHoldAlreadyReleased() {
        HoldState released = HoldState.builder()
                .holdId("hold-10")
                .status("RELEASED")
                .appliedEventIds(new ArrayList<>())
                .build();

        when(holdStateRepository.findById("hold-10")).thenReturn(Optional.of(released));

        service.apply(created("hold-10", List.of("msg-1")));

        // Replaying an old create must not re-protect messages that are free.
        verify(collection, never()).updateMany(any(Bson.class), anyList());
        verify(holdStateRepository).save(any(HoldState.class));
    }

    @Test
    void recordsTheNumberOfMessagesAffected() {
        when(updateResult.getModifiedCount()).thenReturn(37L);

        service.apply(created("hold-11", List.of("msg-1")));

        ArgumentCaptor<HoldState> captor = ArgumentCaptor.forClass(HoldState.class);
        verify(holdStateRepository).save(captor.capture());

        assertThat(captor.getValue().getMessagesAffected()).isEqualTo(37L);
    }

    // -------------------------------------------------------------- helpers

    private CaseHoldEvent created(String holdId, List<String> messageIds) {
        return CaseHoldEvent.builder()
                .eventType(CaseHoldEvent.HOLD_CREATED)
                .eventId(UUID.randomUUID().toString())
                .holdId(holdId)
                .caseId("case-1")
                .status("ACTIVE")
                .scope(CaseHoldEvent.SCOPE_COMMUNICATION)
                .communicationIds(messageIds)
                .occurredAt(Instant.now())
                .build();
    }

    private CaseHoldEvent released(String holdId) {
        return CaseHoldEvent.builder()
                .eventType(CaseHoldEvent.HOLD_RELEASED)
                .eventId(UUID.randomUUID().toString())
                .holdId(holdId)
                .caseId("case-1")
                .status("RELEASED")
                .occurredAt(Instant.now())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Bson> capturePipeline() {
        ArgumentCaptor<List<Bson>> captor = ArgumentCaptor.forClass(List.class);
        verify(collection).updateMany(any(Bson.class), captor.capture());
        return captor.getValue();
    }

    private Bson captureFilter() {
        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateMany(captor.capture(), anyList());
        return captor.getValue();
    }
}
