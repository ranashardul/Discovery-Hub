package com.stown.exportaudit.messaging;

import com.stown.exportaudit.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The case-hold outbox publishes raw JSON text, so this listener takes a
 * String and parses it. It used to take a {@code Map} and rely on the default
 * consumer factory, which binds every value to {@code ExportRequestedEvent} —
 * so Spring could not invoke it and every case and hold event was dead
 * lettered instead of audited.
 */
class CaseHoldEventListenerTest {

    private AuditService auditService;
    private CaseHoldEventListener listener;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        listener = new CaseHoldEventListener(auditService, new ObjectMapper());
    }

    @Test
    void recordsAHoldEventAgainstTheHoldAsTarget() {
        listener.onCaseHoldEvent("""
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "e-1",
                  "caseId": "case-1",
                  "holdId": "hold-1",
                  "createdBy": "asritha",
                  "name": "Preservation"
                }
                """);

        AuditEvent recorded = capture();

        assertThat(recorded.getAction()).isEqualTo("HOLD_CREATED");
        assertThat(recorded.getCaseId()).isEqualTo("case-1");
        assertThat(recorded.getTargetType()).isEqualTo("HOLD");
        assertThat(recorded.getTargetId()).isEqualTo("hold-1");
        assertThat(recorded.getActor()).isEqualTo("asritha");
        // eventId is what makes recording idempotent under redelivery.
        assertThat(recorded.getEventId()).isEqualTo("e-1");
    }

    @Test
    void recordsACaseEventAgainstTheCaseWhenThereIsNoHold() {
        listener.onCaseHoldEvent("""
                {
                  "eventType": "CASE_CREATED",
                  "eventId": "e-2",
                  "caseId": "case-2",
                  "createdBy": "asritha"
                }
                """);

        AuditEvent recorded = capture();

        assertThat(recorded.getTargetType()).isEqualTo("CASE");
        assertThat(recorded.getTargetId()).isEqualTo("case-2");
    }

    @Test
    void takesTheActorFromWhicheverFieldTheEventTypeCarries() {
        listener.onCaseHoldEvent("""
                {"eventType":"HOLD_RELEASED","eventId":"e-3","holdId":"hold-3","releasedBy":"reviewer"}
                """);

        assertThat(capture().getActor()).isEqualTo("reviewer");
    }

    @Test
    void keepsTheRemainingFieldsAsDetailsWithoutDuplicatingTheIdentifiers() {
        listener.onCaseHoldEvent("""
                {"eventType":"HOLD_CREATED","eventId":"e-4","caseId":"case-4","holdId":"hold-4","reason":"litigation"}
                """);

        AuditEvent recorded = capture();

        assertThat(recorded.getDetails()).containsEntry("reason", "litigation");
        assertThat(recorded.getDetails()).doesNotContainKeys("eventType", "eventId", "caseId");
    }

    /**
     * A malformed payload must not stall the partition: the case-hold service
     * owns the format and retrying will never make it parse.
     */
    @Test
    void dropsAnUnparseablePayloadWithoutRecordingAnything() {
        listener.onCaseHoldEvent("not json at all");

        verify(auditService, never()).record(any(AuditEvent.class));
    }

    @Test
    void dropsAnEventWithNoEventType() {
        listener.onCaseHoldEvent("{\"caseId\":\"case-5\"}");

        verify(auditService, never()).record(any(AuditEvent.class));
    }

    @Test
    void dropsBlankAndNullPayloads() {
        listener.onCaseHoldEvent("");
        listener.onCaseHoldEvent(null);

        verify(auditService, never()).record(any(AuditEvent.class));
    }

    private AuditEvent capture() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }
}
