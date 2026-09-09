package com.stown.ingestion.messaging;

import com.stown.ingestion.service.LegalHoldProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the {@code case-hold.events} listener.
 *
 * <p>That topic is owned by the case-hold service and carries every Case and
 * Hold event type as raw JSON text. The listener therefore has to route on the
 * payload rather than a type header, and must not let an event it does not
 * understand stall the partition.
 */
@ExtendWith(MockitoExtension.class)
class CaseHoldEventListenerTest {

    @Mock
    private LegalHoldProjectionService projectionService;

    private CaseHoldEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new CaseHoldEventListener(projectionService, new ObjectMapper());
    }

    @Test
    void appliesAHoldCreatedEvent() {
        listener.onCaseHoldEvent("""
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "e1",
                  "holdId": "hold-1",
                  "caseId": "case-1",
                  "scope": "COMMUNICATION",
                  "communicationIds": ["msg-1"]
                }
                """);

        ArgumentCaptor<CaseHoldEvent> captor = ArgumentCaptor.forClass(CaseHoldEvent.class);
        verify(projectionService).apply(captor.capture());

        CaseHoldEvent event = captor.getValue();
        assertThat(event.isHoldCreated()).isTrue();
        assertThat(event.getHoldId()).isEqualTo("hold-1");
        assertThat(event.getCommunicationIds()).containsExactly("msg-1");
    }

    @Test
    void appliesAHoldReleasedEvent() {
        listener.onCaseHoldEvent("""
                {"eventType":"HOLD_RELEASED","eventId":"e2","holdId":"hold-1","status":"RELEASED"}
                """);

        ArgumentCaptor<CaseHoldEvent> captor = ArgumentCaptor.forClass(CaseHoldEvent.class);
        verify(projectionService).apply(captor.capture());

        assertThat(captor.getValue().isHoldReleased()).isTrue();
    }

    @Test
    void ignoresCaseLifecycleEventsThatShareTheTopic() {
        listener.onCaseHoldEvent("""
                {"eventType":"CASE_CREATED","eventId":"e3","caseId":"case-9"}
                """);
        listener.onCaseHoldEvent("""
                {"eventType":"COMMUNICATION_ADDED_TO_CASE","eventId":"e4","caseId":"case-9"}
                """);

        // One topic carries every domain event; only holds concern us.
        verifyNoInteractions(projectionService);
    }

    @Test
    void discardsAMalformedPayloadWithoutFailing() {
        // Throwing here would retry and dead-letter forever: the producer owns
        // the format and a broken record is not recoverable on this side.
        assertThatCode(() -> listener.onCaseHoldEvent("{ this is not json"))
                .doesNotThrowAnyException();

        verify(projectionService, never()).apply(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ignoresNullAndBlankPayloads() {
        listener.onCaseHoldEvent(null);
        listener.onCaseHoldEvent("");
        listener.onCaseHoldEvent("   ");

        verifyNoInteractions(projectionService);
    }

    @Test
    void ignoresAnEventWithoutAnEventType() {
        listener.onCaseHoldEvent("""
                {"eventId":"e5","holdId":"hold-1"}
                """);

        verifyNoInteractions(projectionService);
    }

    @Test
    void toleratesFieldsTheProducerAddsLater() {
        listener.onCaseHoldEvent("""
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "e6",
                  "holdId": "hold-2",
                  "somethingAddedLater": {"nested": true},
                  "anotherNewField": 42
                }
                """);

        // Unknown properties must not break ingestion; the two services deploy
        // independently.
        verify(projectionService).apply(org.mockito.ArgumentMatchers.any(CaseHoldEvent.class));
    }

    @Test
    void routesCaseInsensitivelyOnEventType() {
        listener.onCaseHoldEvent("""
                {"eventType":"hold_created","eventId":"e7","holdId":"hold-3"}
                """);

        verify(projectionService).apply(org.mockito.ArgumentMatchers.any(CaseHoldEvent.class));
    }
}
