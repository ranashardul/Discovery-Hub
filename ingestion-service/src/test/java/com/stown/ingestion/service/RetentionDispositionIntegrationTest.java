package com.stown.ingestion.service;

import com.stown.ingestion.AbstractIntegrationTest;
import com.stown.ingestion.api.ApiErrorResponse;
import com.stown.ingestion.api.DispositionRunResponse;
import com.stown.ingestion.api.IngestionResponse;
import com.stown.ingestion.api.RetentionStatusResponse;
import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.DispositionOutcome;
import com.stown.ingestion.domain.DispositionRun;
import com.stown.ingestion.domain.DispositionStatus;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.messaging.CaseHoldEvent;
import com.stown.ingestion.repository.DispositionAuditRepository;
import com.stown.ingestion.repository.HoldStateRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end retention and legal-hold behaviour (PRD FR-4.2, FR-4.5, FR-4.6,
 * FR-5.2, FR-5.3).
 *
 * <p>Holds are injected as raw JSON on {@code case-hold.events}, exactly as
 * the case-hold service publishes them, so these tests exercise the real
 * contract without depending on that service running.
 */
class RetentionDispositionIntegrationTest extends AbstractIntegrationTest {

    private static final String MESSAGES_PATH = "/api/ingestion/messages";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private DispositionService dispositionService;

    @Autowired
    private DispositionAuditRepository auditRepository;

    @Autowired
    private HoldStateRepository holdStateRepository;

    @Autowired
    private RetentionProperties retentionProperties;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void resetDispositionState() {
        auditRepository.deleteAll();
        holdStateRepository.deleteAll();
        mongoTemplate.remove(new Query(), DispositionRun.class);
        retentionProperties.setDryRun(false);
    }

    @Test
    void deletesMessageAndAttachmentOnceRetentionExpires() {
        try (Consumer<String, String> disposed = consumer("message.disposed")) {
            String messageId = ingestWithAttachment("ret-expire-1");
            MessageDocument stored = messageRepository.findById(messageId).orElseThrow();
            String s3Key = stored.getAttachments().getFirst().getS3Key();

            awaitRetentionExpiry(messageId);

            DispositionRun run = dispositionService.disposeExpired();

            assertThat(run.getDeleted()).isEqualTo(1);
            assertThat(run.getSkippedOnHold()).isZero();
            assertThat(run.getS3ObjectsPurged()).isEqualTo(1);
            assertThat(messageRepository.findById(messageId)).isEmpty();

            DispositionAudit audit = auditRepository
                    .findFirstByMessageIdOrderByDecidedAtDesc(messageId)
                    .orElseThrow();
            assertThat(audit.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
            assertThat(audit.getReason()).isEqualTo("RETENTION");
            assertThat(audit.isObjectsPurged()).isTrue();
            assertThat(audit.getS3Keys()).containsExactly(s3Key);

            ConsumerRecord<String, String> event = poll(disposed, messageId);
            assertThat(event).isNotNull();
            assertThat(event.value()).contains(messageId).contains("RETENTION");
        }
    }

    @Test
    void holdBlocksDispositionUntilItIsReleased() {
        String messageId = ingestSimple("hold-block-1");
        String holdId = "hold-" + UUID.randomUUID();

        publishHoldCreated(holdId, List.of(messageId));
        awaitHoldCount(messageId, 1);

        MessageDocument held = messageRepository.findById(messageId).orElseThrow();
        assertThat(held.getDispositionStatus()).isEqualTo(DispositionStatus.ON_HOLD);
        assertThat(held.getHoldIds()).containsExactly(holdId);

        awaitRetentionExpiry(messageId);

        // Repeated runs must never remove evidence under hold.
        for (int attempt = 0; attempt < 3; attempt++) {
            DispositionRun run = dispositionService.disposeExpired();
            assertThat(run.getDeleted()).isZero();
            assertThat(run.getSkippedOnHold()).isEqualTo(1);
        }

        assertThat(messageRepository.findById(messageId)).isPresent();
        assertThat(auditRepository.findFirstByMessageIdOrderByDecidedAtDesc(messageId)
                .orElseThrow()
                .getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_ON_HOLD);

        publishHoldReleased(holdId);
        awaitHoldCount(messageId, 0);

        MessageDocument released = messageRepository.findById(messageId).orElseThrow();
        assertThat(released.getDispositionStatus()).isEqualTo(DispositionStatus.ACTIVE);

        DispositionRun finalRun = dispositionService.disposeExpired();

        assertThat(finalRun.getDeleted()).isEqualTo(1);
        assertThat(messageRepository.findById(messageId)).isEmpty();
    }

    @Test
    void overlappingHoldsKeepProtectionUntilTheLastOneIsReleased() {
        String messageId = ingestSimple("overlap-1");
        String first = "hold-a-" + UUID.randomUUID();
        String second = "hold-b-" + UUID.randomUUID();

        publishHoldCreated(first, List.of(messageId));
        awaitHoldCount(messageId, 1);
        publishHoldCreated(second, List.of(messageId));
        awaitHoldCount(messageId, 2);

        awaitRetentionExpiry(messageId);

        publishHoldReleased(first);
        awaitHoldCount(messageId, 1);

        assertThat(dispositionService.disposeExpired().getDeleted()).isZero();
        assertThat(messageRepository.findById(messageId)).isPresent();

        publishHoldReleased(second);
        awaitHoldCount(messageId, 0);

        assertThat(dispositionService.disposeExpired().getDeleted()).isEqualTo(1);
        assertThat(messageRepository.findById(messageId)).isEmpty();
    }

    @Test
    void redeliveredHoldEventDoesNotDoubleCount() {
        String messageId = ingestSimple("dupe-1");
        String holdId = "hold-dupe-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        // The case-hold outbox re-sends on failure, so the same event can
        // arrive more than once. A counter would drift; a set cannot.
        publishHoldCreated(holdId, List.of(messageId), eventId);
        awaitHoldCount(messageId, 1);
        publishHoldCreated(holdId, List.of(messageId), eventId);
        publishHoldCreated(holdId, List.of(messageId), UUID.randomUUID().toString());

        sleep(1500);

        MessageDocument message = messageRepository.findById(messageId).orElseThrow();
        assertThat(message.getHoldCount()).isEqualTo(1);
        assertThat(message.getHoldIds()).containsExactly(holdId);

        // One release still frees it, which would not hold with an increment.
        publishHoldReleased(holdId);
        awaitHoldCount(messageId, 0);
    }

    @Test
    void criteriaScopeHoldsOnlyMatchingMessages() {
        String matching = ingestSimple("criteria-match", "EMAIL", "alice.sharma@example-bank.test");
        String otherSender = ingestSimple("criteria-other", "EMAIL", "zoe.other@example-bank.test");
        String otherType = ingestSimple("criteria-chat", "CHAT", "alice.sharma@example-bank.test");

        String holdId = "hold-criteria-" + UUID.randomUUID();
        publishCriteriaHold(holdId, List.of("alice.sharma@example-bank.test"), List.of("EMAIL"));

        awaitHoldCount(matching, 1);
        sleep(500);

        assertThat(messageRepository.findById(otherSender).orElseThrow().getHoldCount()).isZero();
        assertThat(messageRepository.findById(otherType).orElseThrow().getHoldCount()).isZero();
    }

    @Test
    void deleteApiRefusesAHeldMessageWithConflict() {
        String messageId = ingestSimple("refuse-1");
        String holdId = "hold-refuse-" + UUID.randomUUID();

        publishHoldCreated(holdId, List.of(messageId));
        awaitHoldCount(messageId, 1);

        ResponseEntity<ApiErrorResponse> response = restTemplate.exchange(
                url(MESSAGES_PATH + "/" + messageId),
                org.springframework.http.HttpMethod.DELETE,
                null,
                ApiErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message())
                .contains("legal hold")
                .contains(holdId);
        assertThat(messageRepository.findById(messageId)).isPresent();
    }

    @Test
    void deleteApiRemovesAnUnheldMessage() {
        String messageId = ingestSimple("manual-1");

        ResponseEntity<Map> response = restTemplate.exchange(
                url(MESSAGES_PATH + "/" + messageId),
                org.springframework.http.HttpMethod.DELETE,
                null,
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "DISPOSED");
        assertThat(messageRepository.findById(messageId)).isEmpty();

        DispositionAudit audit = auditRepository
                .findFirstByMessageIdOrderByDecidedAtDesc(messageId)
                .orElseThrow();
        assertThat(audit.getReason()).isEqualTo("MANUAL");
    }

    @Test
    void aHoldAppliedDuringTheRunStopsTheDelete() {
        String messageId = ingestSimple("race-1");
        awaitRetentionExpiry(messageId);

        // Simulates a hold landing after the candidate was read but before the
        // delete executes: the conditional delete must match nothing.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(messageId)),
                new Update().set("holdCount", 1).set("holdIds", List.of("hold-race")),
                MessageDocument.class
        );

        DispositionRun run = dispositionService.disposeExpired();

        assertThat(run.getDeleted()).isZero();
        assertThat(run.getSkippedOnHold()).isEqualTo(1);
        assertThat(messageRepository.findById(messageId)).isPresent();
    }

    @Test
    void dryRunReportsWithoutDeleting() {
        String messageId = ingestSimple("dry-1");
        awaitRetentionExpiry(messageId);

        retentionProperties.setDryRun(true);

        DispositionRun run = dispositionService.disposeExpired();

        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getDeleted()).isEqualTo(1);
        assertThat(messageRepository.findById(messageId))
                .describedAs("dry run must not delete anything")
                .isPresent();
        assertThat(auditRepository.findFirstByMessageIdOrderByDecidedAtDesc(messageId)).isEmpty();
    }

    @Test
    void marksTheOriginatingRequestDisposedSoReSubmissionIsNotSilentlyDeduplicated() {
        String messageId = ingestSimple("disposed-request-1");
        awaitRetentionExpiry(messageId);

        dispositionService.disposeExpired();

        var request = ingestionRequestRepository.findByExternalMessageId("disposed-request-1")
                .orElseThrow();
        assertThat(request.getStatus()).isEqualTo(IngestionStatus.DISPOSED);
    }

    @Test
    void retentionStatusEndpointExposesTheCountdownAndHoldState() {
        String messageId = ingestSimple("status-1");

        RetentionStatusResponse status = restTemplate.getForObject(
                url(MESSAGES_PATH + "/" + messageId + "/retention"),
                RetentionStatusResponse.class
        );

        assertThat(status.messageId()).isEqualTo(messageId);
        assertThat(status.held()).isFalse();
        assertThat(status.holdCount()).isZero();
        assertThat(status.retentionUntil()).isNotNull();
        assertThat(status.dispositionStatus()).isEqualTo(DispositionStatus.ACTIVE);
    }

    @Test
    void recordsEachRunWithDeletedAndSkippedCounts() {
        String held = ingestSimple("run-held");
        String free = ingestSimple("run-free");

        String holdId = "hold-run-" + UUID.randomUUID();
        publishHoldCreated(holdId, List.of(held));
        awaitHoldCount(held, 1);

        awaitRetentionExpiry(free);

        dispositionService.disposeExpired();

        DispositionRun stored = mongoTemplate.findAll(DispositionRun.class).getFirst();

        // FR-5.3: the run must record what went, what was spared, and when.
        assertThat(stored.getScanned()).isEqualTo(2);
        assertThat(stored.getDeleted()).isEqualTo(1);
        assertThat(stored.getSkippedOnHold()).isEqualTo(1);
        assertThat(stored.getFinishedAt()).isNotNull();

        // Same evidence through the API used in the demo.
        DispositionRunResponse[] runs = restTemplate.getForObject(
                url("/api/ingestion/disposition/runs?limit=5"),
                DispositionRunResponse[].class
        );

        assertThat(runs).hasSize(1);
        assertThat(runs[0].deleted()).isEqualTo(1);
        assertThat(runs[0].skippedOnHold()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private String ingestSimple(String externalId) {
        return ingestSimple(externalId, "EMAIL", "alice.sharma@example-bank.test");
    }

    private String ingestSimple(String externalId, String type, String sender) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "communicationType", type,
                "sender", sender,
                "recipients", List.of("bob.patel@example-bank.test"),
                "subject", "Retention test " + externalId,
                "body", "Body for " + externalId,
                "messageTimestamp", "2026-09-08T03:00:00Z",
                "externalMessageId", externalId
        ));

        return awaitIngestion(body, externalId);
    }

    private String ingestWithAttachment(String externalId) {
        byte[] content = new byte[2048];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index % 251);
        }

        Map<String, Object> body = new HashMap<>(Map.of(
                "communicationType", "EMAIL",
                "sender", "alice.sharma@example-bank.test",
                "recipients", List.of("bob.patel@example-bank.test"),
                "subject", "Retention test " + externalId,
                "body", "Body for " + externalId,
                "messageTimestamp", "2026-09-08T03:00:00Z",
                "externalMessageId", externalId,
                "attachments", List.of(Map.of(
                        "filename", "credit-memo.pdf",
                        "contentType", "application/pdf",
                        "contentBase64", java.util.Base64.getEncoder().encodeToString(content)
                ))
        ));

        return awaitIngestion(body, externalId);
    }

    private String awaitIngestion(Map<String, Object> body, String externalId) {
        IngestionResponse accepted = restTemplate.postForObject(
                url(MESSAGES_PATH),
                body,
                IngestionResponse.class
        );

        assertThat(accepted).isNotNull();

        await(() -> ingestionRequestRepository.findById(accepted.requestId())
                .filter(request -> request.getStatus() == IngestionStatus.INGESTED)
                .isPresent());

        return ingestionRequestRepository.findById(accepted.requestId())
                .orElseThrow()
                .getMessageId();
    }

    private void awaitRetentionExpiry(String messageId) {
        await(() -> messageRepository.findById(messageId)
                .map(message -> !message.getRetentionUntil().isAfter(Instant.now()))
                .orElse(false));
    }

    private void awaitHoldCount(String messageId, int expected) {
        await(() -> messageRepository.findById(messageId)
                .map(message -> message.getHoldCount() == expected)
                .orElse(false));
    }

    private void publishHoldCreated(String holdId, List<String> messageIds) {
        publishHoldCreated(holdId, messageIds, UUID.randomUUID().toString());
    }

    private void publishHoldCreated(String holdId, List<String> messageIds, String eventId) {
        String ids = messageIds.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");

        publish(holdId, """
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "%s",
                  "holdId": "%s",
                  "caseId": "case-test",
                  "status": "ACTIVE",
                  "scope": "COMMUNICATION",
                  "communicationIds": [%s],
                  "occurredAt": "%s"
                }
                """.formatted(eventId, holdId, ids, Instant.now()));
    }

    private void publishCriteriaHold(String holdId, List<String> participants, List<String> types) {
        String quotedParticipants = quote(participants);
        String quotedTypes = quote(types);

        publish(holdId, """
                {
                  "eventType": "HOLD_CREATED",
                  "eventId": "%s",
                  "holdId": "%s",
                  "caseId": "case-test",
                  "status": "ACTIVE",
                  "scope": "CRITERIA",
                  "criteriaParticipants": [%s],
                  "criteriaCommunicationTypes": [%s],
                  "occurredAt": "%s"
                }
                """.formatted(
                UUID.randomUUID(), holdId, quotedParticipants, quotedTypes, Instant.now()));
    }

    private void publishHoldReleased(String holdId) {
        publish(holdId, """
                {
                  "eventType": "HOLD_RELEASED",
                  "eventId": "%s",
                  "holdId": "%s",
                  "caseId": "case-test",
                  "status": "RELEASED",
                  "releasedAt": "%s",
                  "occurredAt": "%s"
                }
                """.formatted(UUID.randomUUID(), holdId, Instant.now(), Instant.now()));
    }

    /**
     * Publishes exactly as the case-hold service does: a raw JSON string with
     * the hold id as the key and an {@code event_type} header.
     */
    private void publish(String key, String payload) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        config.put(ProducerConfig.ACKS_CONFIG, "all");

        try (Producer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>("case-hold.events", key, payload);
            record.headers().add("event_type", eventTypeOf(payload).getBytes());
            producer.send(record);
            producer.flush();
        }
    }

    private String eventTypeOf(String payload) {
        return payload.contains(CaseHoldEvent.HOLD_RELEASED)
                ? CaseHoldEvent.HOLD_RELEASED
                : CaseHoldEvent.HOLD_CREATED;
    }

    private String quote(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private Consumer<String, String> consumer(String topic) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        Consumer<String, String> consumer = new KafkaConsumer<>(
                properties, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofSeconds(2));

        return consumer;
    }

    private ConsumerRecord<String, String> poll(Consumer<String, String> consumer, String key) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : records) {
                if (key == null || key.equals(record.key())) {
                    return record;
                }
            }
        }

        return null;
    }

    private void await(Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            sleep(200);
        }

        throw new AssertionError("Condition not met within " + TIMEOUT);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
