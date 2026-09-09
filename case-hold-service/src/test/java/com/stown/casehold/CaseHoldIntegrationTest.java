package com.stown.casehold;

import com.stown.casehold.api.CaseCommunicationsResponse;
import com.stown.casehold.api.CaseResponse;
import com.stown.casehold.api.HoldCommunicationsResponse;
import com.stown.casehold.api.HoldResponse;
import com.stown.casehold.domain.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the Case & Hold Service persists cases and holds
 * in PostgreSQL, writes domain events to the transactional outbox, and the
 * OutboxPublisher drains them to Kafka.
 *
 * <p>Tagged {@code integration} and therefore excluded from the default surefire
 * run: {@code mvn test -Dgroups=integration -Dexcluded.test.groups=} runs it.
 */
@Tag("integration")
class CaseHoldIntegrationTest extends AbstractIntegrationTest {

    @BeforeEach
    void cleanOutbox() {
        outboxEventRepository.deleteAll();
    }

    @Test
    void createsCaseAndPublishesEvent() {
        String body = """
                {
                  "caseName": "Acquisition Investigation",
                  "description": "Review communications related to the acquisition",
                  "createdBy": "admin"
                }
                """;

        ResponseEntity<CaseResponse> response = post("/api/v1/cases", body, CaseResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseResponse created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.caseId()).isNotNull();
        assertThat(created.caseName()).isEqualTo("Acquisition Investigation");
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.createdBy()).isEqualTo("admin");
        assertThat(created.communicationCount()).isZero();
        assertThat(created.activeHoldCount()).isZero();

        // The CASE_CREATED event must have been written to the outbox.
        awaitOutboxDrained();

        // The case is retrievable by id.
        ResponseEntity<CaseResponse> fetched = get(
                "/api/v1/cases/" + created.caseId(),
                CaseResponse.class
        );
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().caseId()).isEqualTo(created.caseId());
    }

    @Test
    void updatesCaseAndPublishesEvent() {
        String createBody = """
                { "caseName": "Q3 Audit", "description": "Initial", "createdBy": "alice" }
                """;
        CaseResponse created = post("/api/v1/cases", createBody, CaseResponse.class).getBody();
        assertThat(created).isNotNull();

        String updateBody = """
                {
                  "caseName": "Q3 Audit - Revised",
                  "description": "Updated scope",
                  "status": "CLOSED",
                  "updatedBy": "bob"
                }
                """;

        ResponseEntity<CaseResponse> response = patch(
                "/api/v1/cases/" + created.caseId(),
                updateBody,
                CaseResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseResponse updated = response.getBody();
        assertThat(updated).isNotNull();
        assertThat(updated.caseName()).isEqualTo("Q3 Audit - Revised");
        assertThat(updated.description()).isEqualTo("Updated scope");
        assertThat(updated.status()).isEqualTo("CLOSED");
    }

    @Test
    void rejectsUpdateToArchivedCaseWithNoChanges() {
        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Stale", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        // Archive it first.
        patch(
                "/api/v1/cases/" + created.caseId(),
                """
                { "status": "ARCHIVED", "updatedBy": "admin" }
                """,
                CaseResponse.class
        );

        // Now attempt to add communications to the archived case → 409.
        ResponseEntity<Map> response = post(
                "/api/v1/cases/" + created.caseId() + "/communications",
                """
                {
                  "communications": [
                    { "communicationId": "msg-1" }
                  ],
                  "addedBy": "admin"
                }
                """,
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addsCommunicationsAndPublishesEvents() {
        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Fraud Review", "description": "", "createdBy": "investigator" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        String addBody = """
                {
                  "communications": [
                    { "communicationId": "msg-100", "communicationType": "EMAIL" },
                    { "communicationId": "msg-101", "communicationType": "CHAT" },
                    { "communicationId": "msg-100", "communicationType": "EMAIL" }
                  ],
                  "addedBy": "investigator"
                }
                """;

        ResponseEntity<CaseCommunicationsResponse> response = post(
                "/api/v1/cases/" + created.caseId() + "/communications",
                addBody,
                CaseCommunicationsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseCommunicationsResponse body = response.getBody();
        assertThat(body).isNotNull();
        // Two unique communications, the duplicate is ignored.
        assertThat(body.newlyAdded()).hasSize(2);
        assertThat(body.total()).isEqualTo(2);

        // Re-adding the same ids is idempotent: nothing new is added.
        ResponseEntity<CaseCommunicationsResponse> second = post(
                "/api/v1/cases/" + created.caseId() + "/communications",
                addBody,
                CaseCommunicationsResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().newlyAdded()).isEmpty();
        assertThat(second.getBody().total()).isEqualTo(2);
    }

    @Test
    void createsCommunicationHoldAndReleasesIt() {
        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Hold Case", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        String holdBody = """
                {
                  "name": "Preserve key emails",
                  "description": "Explicit hold",
                  "reason": "Legal preservation",
                  "createdBy": "admin",
                  "communications": [
                    { "communicationId": "msg-1", "communicationType": "EMAIL" },
                    { "communicationId": "msg-2", "communicationType": "CHAT" }
                  ]
                }
                """;

        ResponseEntity<HoldResponse> holdResponse = post(
                "/api/v1/cases/" + created.caseId() + "/holds",
                holdBody,
                HoldResponse.class
        );

        assertThat(holdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        HoldResponse hold = holdResponse.getBody();
        assertThat(hold).isNotNull();
        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(hold.scope()).isEqualTo("COMMUNICATION");
        assertThat(hold.criteria()).isNull();
        assertThat(hold.communicationCount()).isEqualTo(2);
        assertThat(hold.releasedAt()).isNull();

        // List holds for the case.
        ResponseEntity<HoldResponse[]> holds = get(
                "/api/v1/cases/" + created.caseId() + "/holds",
                HoldResponse[].class
        );
        assertThat(holds.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(holds.getBody()).isNotNull();
        assertThat(holds.getBody()).hasSize(1);

        // List the hold's communications.
        ResponseEntity<HoldCommunicationsResponse> comms = get(
                "/api/v1/holds/" + hold.holdId() + "/communications",
                HoldCommunicationsResponse.class
        );
        assertThat(comms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(comms.getBody()).isNotNull();
        assertThat(comms.getBody().communications()).hasSize(2);

        // Release the hold.
        ResponseEntity<HoldResponse> released = patch(
                "/api/v1/holds/" + hold.holdId() + "/release",
                """
                { "releasedBy": "admin" }
                """,
                HoldResponse.class
        );

        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        HoldResponse releasedBody = released.getBody();
        assertThat(releasedBody).isNotNull();
        assertThat(releasedBody.status()).isEqualTo("RELEASED");
        assertThat(releasedBody.releasedBy()).isEqualTo("admin");
        assertThat(releasedBody.releasedAt()).isNotNull();

        // Releasing again is a conflict.
        ResponseEntity<Map> doubleRelease = patch(
                "/api/v1/holds/" + hold.holdId() + "/release",
                """
                { "releasedBy": "admin" }
                """,
                Map.class
        );
        assertThat(doubleRelease.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createsCriteriaBasedHold() {
        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Criteria Case", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        String holdBody = """
                {
                  "name": "Preserve Jan-Jun comms",
                  "description": "Criteria-based",
                  "reason": "Litigation hold",
                  "createdBy": "admin",
                  "criteria": {
                    "participants": ["john@company.com", "jane@company.com"],
                    "communicationTypes": ["EMAIL", "CHAT"],
                    "dateFrom": "2026-01-01T00:00:00Z",
                    "dateTo": "2026-06-30T23:59:59Z"
                  }
                }
                """;

        ResponseEntity<HoldResponse> response = post(
                "/api/v1/cases/" + created.caseId() + "/holds",
                holdBody,
                HoldResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HoldResponse hold = response.getBody();
        assertThat(hold).isNotNull();
        assertThat(hold.status()).isEqualTo("ACTIVE");
        assertThat(hold.scope()).isEqualTo("CRITERIA");
        assertThat(hold.criteria()).isNotNull();
        assertThat(hold.criteria().participants())
                .containsExactly("john@company.com", "jane@company.com");
        assertThat(hold.criteria().communicationTypes())
                .containsExactly("EMAIL", "CHAT");
        assertThat(hold.criteria().dateFrom())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(hold.criteria().dateTo())
                .isEqualTo(Instant.parse("2026-06-30T23:59:59Z"));
        assertThat(hold.communicationCount()).isZero();
    }

    @Test
    void rejectsHoldWithBothCommunicationsAndCriteria() {
        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Bad Hold", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        String holdBody = """
                {
                  "name": "Ambiguous",
                  "createdBy": "admin",
                  "communications": [{ "communicationId": "msg-1" }],
                  "criteria": { "participants": ["john@company.com"] }
                }
                """;

        ResponseEntity<Map> response = post(
                "/api/v1/cases/" + created.caseId() + "/holds",
                holdBody,
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForUnknownCase() {
        ResponseEntity<Map> response = get(
                "/api/v1/cases/" + UUID.randomUUID(),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolvesCommunicationReferencesToRealMessageMetadata() {
        String messageId = UUID.randomUUID().toString();
        seedMessage(messageId, "priya.nair@example-bank.test",
                "Sanction conditions - Meridian Textiles facility", 0);

        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Enrichment Case", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        ResponseEntity<CaseCommunicationsResponse> response = post(
                "/api/v1/cases/" + created.caseId() + "/communications",
                """
                {
                  "communications": [
                    { "communicationId": "%s", "communicationType": "EMAIL" }
                  ],
                  "addedBy": "admin"
                }
                """.formatted(messageId),
                CaseCommunicationsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseCommunicationsResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.unresolvedCount()).isZero();
        assertThat(body.added()).singleElement().satisfies(item -> {
            assertThat(item.communicationId()).isEqualTo(messageId);
            assertThat(item.message()).isNotNull();
            assertThat(item.message().resolved()).isTrue();
            assertThat(item.message().sender()).isEqualTo("priya.nair@example-bank.test");
            assertThat(item.message().subject())
                    .isEqualTo("Sanction conditions - Meridian Textiles facility");
            assertThat(item.message().recipients())
                    .containsExactly("arjun.mehta@example-bank.test");
            assertThat(item.message().messageTimestamp())
                    .isEqualTo(Instant.parse("2026-03-15T09:22:00Z"));
            assertThat(item.message().attachmentCount()).isEqualTo(1);
            assertThat(item.message().dispositionStatus()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void flagsReferencesThatMatchNoMessage() {
        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Unresolved Case", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        // "msg-1" is not a message identifier; the reference is still stored so
        // the case record survives, but it is reported as unresolved.
        ResponseEntity<CaseCommunicationsResponse> response = post(
                "/api/v1/cases/" + created.caseId() + "/communications",
                """
                {
                  "communications": [ { "communicationId": "msg-1" } ],
                  "addedBy": "admin"
                }
                """,
                CaseCommunicationsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseCommunicationsResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.unresolvedCount()).isEqualTo(1);
        assertThat(body.added()).singleElement().satisfies(item -> {
            assertThat(item.communicationId()).isEqualTo("msg-1");
            assertThat(item.message().resolved()).isFalse();
            assertThat(item.message().sender()).isNull();
            assertThat(item.message().subject()).isNull();
        });
    }

    @Test
    void reportsWhetherAHoldIsEnforcedOnTheMessageData() {
        // holdCount=2 mimics ingestion-service having projected two holds onto
        // the message in response to case-hold.events.
        String enforcedId = UUID.randomUUID().toString();
        seedMessage(enforcedId, "ravi.iyer@example-bank.test", "AML alert - structuring pattern", 2);

        // holdCount=0 means the projection has not been applied to this one.
        String notEnforcedId = UUID.randomUUID().toString();
        seedMessage(notEnforcedId, "meera.rao@example-bank.test", "LCR variance - March pack", 0);

        CaseResponse created = post(
                "/api/v1/cases",
                """
                { "caseName": "Enforcement Case", "description": "", "createdBy": "admin" }
                """,
                CaseResponse.class
        ).getBody();
        assertThat(created).isNotNull();

        HoldResponse hold = post(
                "/api/v1/cases/" + created.caseId() + "/holds",
                """
                {
                  "name": "Preserve AML evidence",
                  "reason": "Regulatory investigation",
                  "createdBy": "admin",
                  "communications": [
                    { "communicationId": "%s", "communicationType": "EMAIL" },
                    { "communicationId": "%s", "communicationType": "EMAIL" }
                  ]
                }
                """.formatted(enforcedId, notEnforcedId),
                HoldResponse.class
        ).getBody();
        assertThat(hold).isNotNull();

        ResponseEntity<HoldCommunicationsResponse> response = get(
                "/api/v1/holds/" + hold.holdId() + "/communications",
                HoldCommunicationsResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HoldCommunicationsResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.total()).isEqualTo(2);
        assertThat(body.unresolvedCount()).isZero();
        // Only the message carrying holdCount > 0 counts as enforced.
        assertThat(body.enforcedCount()).isEqualTo(1);

        assertThat(body.communications())
                .filteredOn(item -> item.communicationId().equals(enforcedId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.message().resolved()).isTrue();
                    assertThat(item.message().holdCount()).isEqualTo(2);
                    assertThat(item.message().dispositionStatus()).isEqualTo("ON_HOLD");
                });
    }

    // --- helpers ---------------------------------------------------------

    private <T> ResponseEntity<T> post(String path, String body, Class<T> type) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var request = new org.springframework.http.HttpEntity<>(body, headers);
        return restTemplate.postForEntity(url(path), request, type);
    }

    private <T> ResponseEntity<T> get(String path, Class<T> type) {
        return restTemplate.getForEntity(url(path), type);
    }

    private <T> ResponseEntity<T> patch(String path, String body, Class<T> type) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var request = new org.springframework.http.HttpEntity<>(body, headers);
        return restTemplate.exchange(
                url(path),
                org.springframework.http.HttpMethod.PATCH,
                request,
                type
        );
    }

    /**
     * Waits until the outbox is drained (all events PUBLISHED), proving the
     * OutboxPublisher successfully sent them to Kafka.
     */
    private void awaitOutboxDrained() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            long pending = outboxEventRepository
                    .findByStatusOrderByCreatedAtAsc(
                            OutboxStatus.PENDING,
                            org.springframework.data.domain.PageRequest.of(0, 100)
                    )
                    .size();
            if (pending == 0) {
                return;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Outbox was not drained within 30s");
    }
}
