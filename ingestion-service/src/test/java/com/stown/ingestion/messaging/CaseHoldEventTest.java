package com.stown.ingestion.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case-hold service owns this payload and publishes it as raw JSON text.
 * These tests pin the shape we depend on and prove we tolerate the parts we
 * do not.
 */
class CaseHoldEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesAHoldCreatedEventWithCommunicationScope() {
        String payload = """
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "8bd7f0f4-1f4a-4a4e-9b0e-1d9f2f7c1a11",
                  "holdId": "hold-1",
                  "caseId": "case-1",
                  "status": "ACTIVE",
                  "scope": "COMMUNICATION",
                  "communicationIds": ["msg-1", "msg-2"],
                  "createdBy": "investigator",
                  "occurredAt": "2026-09-08T10:00:00Z"
                }
                """;

        CaseHoldEvent event = objectMapper.readValue(payload, CaseHoldEvent.class);

        assertThat(event.isHoldCreated()).isTrue();
        assertThat(event.isHoldReleased()).isFalse();
        assertThat(event.isCriteriaScope()).isFalse();
        assertThat(event.getHoldId()).isEqualTo("hold-1");
        assertThat(event.getCommunicationIds()).containsExactly("msg-1", "msg-2");
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
    }

    @Test
    void parsesAHoldCreatedEventWithCriteriaScope() {
        String payload = """
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "e1",
                  "holdId": "hold-2",
                  "scope": "CRITERIA",
                  "criteriaParticipants": ["alice@example-bank.test"],
                  "criteriaCommunicationTypes": ["EMAIL"],
                  "criteriaDateFrom": "2026-01-01T00:00:00Z",
                  "criteriaDateTo": "2026-12-31T23:59:59Z"
                }
                """;

        CaseHoldEvent event = objectMapper.readValue(payload, CaseHoldEvent.class);

        assertThat(event.isCriteriaScope()).isTrue();
        assertThat(event.getCriteriaParticipants()).containsExactly("alice@example-bank.test");
        assertThat(event.getCriteriaCommunicationTypes()).containsExactly("EMAIL");
        assertThat(event.getCriteriaDateFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void parsesAHoldReleasedEventWhichNamesNoMessages() {
        String payload = """
                {
                  "eventType": "HOLD_RELEASED",
                  "eventId": "e2",
                  "holdId": "hold-1",
                  "caseId": "case-1",
                  "status": "RELEASED",
                  "releasedBy": "investigator",
                  "releasedAt": "2026-09-08T11:00:00Z",
                  "occurredAt": "2026-09-08T11:00:00Z"
                }
                """;

        CaseHoldEvent event = objectMapper.readValue(payload, CaseHoldEvent.class);

        assertThat(event.isHoldReleased()).isTrue();
        assertThat(event.getCommunicationIds()).isNull();
        assertThat(event.getHoldId()).isEqualTo("hold-1");
    }

    @Test
    void ignoresPropertiesTheProducerAddsThatWeDoNotConsume() {
        String payload = """
                {
                  "eventType": "HOLD_CREATED",
                  "holdId": "hold-3",
                  "somethingNewTheyAddedLater": {"nested": true},
                  "releasedBy": "someone"
                }
                """;

        CaseHoldEvent event = objectMapper.readValue(payload, CaseHoldEvent.class);

        assertThat(event.getHoldId()).isEqualTo("hold-3");
    }

    @Test
    void treatsCaseLifecycleEventsAsNeitherCreateNorRelease() {
        String payload = """
                { "eventType": "CASE_CREATED", "eventId": "e3", "caseId": "case-9" }
                """;

        CaseHoldEvent event = objectMapper.readValue(payload, CaseHoldEvent.class);

        assertThat(event.isHoldCreated()).isFalse();
        assertThat(event.isHoldReleased()).isFalse();
    }
}
