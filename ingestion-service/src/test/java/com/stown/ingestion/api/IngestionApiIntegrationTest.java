package com.stown.ingestion.api;

import com.stown.ingestion.AbstractIntegrationTest;
import com.stown.ingestion.domain.AttachmentMetadata;
import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.domain.OutboxStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionApiIntegrationTest extends AbstractIntegrationTest {

    private static final String MESSAGES_PATH = "/api/ingestion/messages";
    private static final Duration INGESTION_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(url("/actuator/health"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void acceptsRequestAsynchronouslyAndStoresMessageWithEvent() {
        try (Consumer<String, String> consumer = createConsumer("message.ingested")) {
            IngestionResponse accepted = post(requestBody("corpus-async-1", "thread-async"));

            assertThat(accepted.status()).isEqualTo(IngestionStatus.RECEIVED);
            assertThat(accepted.duplicate()).isFalse();
            assertThat(accepted.requestId()).isNotBlank();
            assertThat(accepted.deduplicationKey()).hasSize(64);
            assertThat(accepted.messageId()).isNull();

            IngestionRequestStatusResponse status = awaitIngested(accepted.requestId());

            assertThat(status.messageId()).isNotBlank();
            assertThat(status.status()).isEqualTo(IngestionStatus.INGESTED);

            MessageDocument stored = messageRepository.findById(status.messageId()).orElseThrow();

            assertThat(stored.getDeduplicationKey()).isEqualTo(accepted.deduplicationKey());
            assertThat(stored.getExternalMessageId()).isEqualTo("corpus-async-1");
            assertThat(stored.getRequestId()).isEqualTo(accepted.requestId());
            assertThat(stored.getSender()).isEqualTo("alice.sharma@example-corp.test");
            assertThat(stored.getDispositionStatus()).isEqualTo("ACTIVE");
            assertThat(stored.getRetentionUntil()).isAfter(stored.getCreatedAt());

            ConsumerRecord<String, String> event = pollForEvent(consumer, status.messageId());

            assertThat(event).isNotNull();
            assertThat(event.key()).isEqualTo(status.messageId());
            assertThat(event.value())
                    .contains(status.messageId())
                    .contains(stored.getDeduplicationKey())
                    .contains("eventId");

            await(() -> messageRepository.findById(status.messageId())
                    .filter(message -> message.getOutboxStatus() == OutboxStatus.PUBLISHED)
                    .isPresent());
        }
    }

    @Test
    void publishesIngestionRequestedEventWithoutAttachmentBinaries() {
        try (Consumer<String, String> consumer = createConsumer("ingestion.requested")) {
            Map<String, Object> request = new HashMap<>(
                    requestBody("corpus-event-1", "thread-event")
            );
            request.put("attachments", List.of(attachment("notes.txt", "text/plain", 4096)));

            IngestionResponse accepted = post(request);
            ConsumerRecord<String, String> event =
                    pollForEvent(consumer, accepted.requestId());

            assertThat(event).isNotNull();
            assertThat(event.value())
                    .contains("stagingKey")
                    .contains("notes.txt")
                    .doesNotContain("contentBase64");
            assertThat(event.value().length()).isLessThan(4096);
        }
    }

    @Test
    void storesAttachmentInObjectStoreAndKeepsMetadataInMongo() {
        Map<String, Object> request = new HashMap<>(
                requestBody("corpus-attachment-1", "thread-attachment")
        );
        request.put("attachments", List.of(
                attachment("budget-review.pdf", "application/pdf", 8192)
        ));

        IngestionResponse accepted = post(request);
        IngestionRequestStatusResponse status = awaitIngested(accepted.requestId());

        MessageDocument stored = messageRepository.findById(status.messageId()).orElseThrow();

        assertThat(stored.getAttachments()).hasSize(1);

        AttachmentMetadata attachment = stored.getAttachments().getFirst();

        assertThat(attachment.getAttachmentId()).isEqualTo("att-001");
        assertThat(attachment.getFilename()).isEqualTo("budget-review.pdf");
        assertThat(attachment.getContentType()).isEqualTo("application/pdf");
        assertThat(attachment.getSizeBytes()).isEqualTo(8192);
        assertThat(attachment.getSha256()).hasSize(64);
        assertThat(attachment.getS3Bucket()).isEqualTo(TEST_BUCKET);
        assertThat(attachment.getS3Key()).isEqualTo(
                "messages/%s/attachments/att-001/budget-review.pdf".formatted(status.messageId())
        );
        assertThat(attachment.getS3Url())
                .startsWith(minioUrl())
                .contains(TEST_BUCKET)
                .contains(status.messageId());

        // The staged copy is removed once the durable object exists.
        IngestionRequestDocument request1 =
                ingestionRequestRepository.findById(accepted.requestId()).orElseThrow();

        assertThat(request1.getStagedAttachments()).isEmpty();
    }

    @Test
    void duplicateRequestIsIdempotent() {
        Map<String, Object> request = requestBody("corpus-duplicate-1", "thread-duplicate");

        IngestionResponse first = post(request);
        IngestionRequestStatusResponse firstStatus = awaitIngested(first.requestId());

        IngestionResponse second = post(request);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.requestId()).isEqualTo(first.requestId());
        assertThat(second.deduplicationKey()).isEqualTo(first.deduplicationKey());
        assertThat(second.messageId()).isEqualTo(firstStatus.messageId());
        assertThat(second.status()).isEqualTo(IngestionStatus.INGESTED);

        assertThat(messageRepository.count()).isEqualTo(1);
        assertThat(ingestionRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void messageIdIsStableAcrossResubmission() {
        Map<String, Object> request = requestBody("corpus-stable-1", "thread-stable");

        String firstMessageId = awaitIngested(post(request).requestId()).messageId();

        post(request);

        String secondMessageId = ingestionRequestRepository
                .findByExternalMessageId("corpus-stable-1")
                .orElseThrow()
                .getMessageId();

        assertThat(secondMessageId).isEqualTo(firstMessageId);
    }

    @Test
    void statusCanBeLookedUpByDeduplicationKeyAndExternalMessageId() {
        IngestionResponse accepted = post(requestBody("corpus-lookup-1", "thread-lookup"));
        awaitIngested(accepted.requestId());

        var byKey = restTemplate.getForEntity(
                url("/api/ingestion/requests?deduplicationKey=" + accepted.deduplicationKey()),
                IngestionRequestStatusResponse.class
        );
        var byExternalId = restTemplate.getForEntity(
                url("/api/ingestion/requests?externalMessageId=corpus-lookup-1"),
                IngestionRequestStatusResponse.class
        );

        assertThat(byKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byKey.getBody().requestId()).isEqualTo(accepted.requestId());
        assertThat(byExternalId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byExternalId.getBody().messageId()).isNotBlank();
    }

    @Test
    void unknownRequestReturnsStructuredNotFound() {
        ResponseEntity<ApiErrorResponse> response = restTemplate.getForEntity(
                url("/api/ingestion/requests/" + UUID.randomUUID()),
                ApiErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo(404);
    }

    @Test
    void uniqueIndexesExistOnIngestionCollections() {
        assertThat(uniqueIndexFields(MessageDocument.class))
                .contains("deduplicationKey", "externalMessageId");
        assertThat(uniqueIndexFields(IngestionRequestDocument.class))
                .contains("deduplicationKey", "externalMessageId");
    }

    @Test
    void invalidRequestReturnsStructuredValidationError() {
        Map<String, Object> request = new HashMap<>(requestBody("corpus-invalid-1", "thread"));
        request.put("sender", "not-an-email");
        request.remove("body");

        ResponseEntity<ApiErrorResponse> response = restTemplate.postForEntity(
                url(MESSAGES_PATH),
                request,
                ApiErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiErrorResponse.FieldViolation::field)
                .contains("sender", "body");
        assertThat(ingestionRequestRepository.count()).isZero();
    }

    @Test
    void invalidAttachmentContentReturnsBadRequest() {
        Map<String, Object> request = new HashMap<>(requestBody("corpus-bad-att", "thread"));
        request.put("attachments", List.of(Map.of(
                "filename", "broken.pdf",
                "contentType", "application/pdf",
                "contentBase64", "not-base-64-!!"
        )));

        ResponseEntity<ApiErrorResponse> response = restTemplate.postForEntity(
                url(MESSAGES_PATH),
                request,
                ApiErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("broken.pdf");
    }

    @Test
    void chatMessagesAreSupported() {
        Map<String, Object> request = new HashMap<>(requestBody("corpus-chat-1", "thread-chat"));
        request.put("communicationType", "CHAT");
        request.put("subject", "Standup notes");

        IngestionRequestStatusResponse status = awaitIngested(post(request).requestId());
        MessageDocument stored = messageRepository.findById(status.messageId()).orElseThrow();

        assertThat(stored.getCommunicationType()).isEqualTo("CHAT");
    }

    private IngestionResponse post(Map<String, Object> request) {
        ResponseEntity<IngestionResponse> response = restTemplate.postForEntity(
                url(MESSAGES_PATH),
                request,
                IngestionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        return response.getBody();
    }

    private IngestionRequestStatusResponse awaitIngested(String requestId) {
        long deadline = System.currentTimeMillis() + INGESTION_TIMEOUT.toMillis();
        IngestionRequestStatusResponse last = null;

        while (System.currentTimeMillis() < deadline) {
            last = restTemplate.getForObject(
                    url("/api/ingestion/requests/" + requestId),
                    IngestionRequestStatusResponse.class
            );

            if (last != null && last.status() == IngestionStatus.INGESTED) {
                return last;
            }

            sleep(200);
        }

        throw new AssertionError("Request " + requestId + " was not ingested in time: " + last);
    }

    private void await(Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + INGESTION_TIMEOUT.toMillis();

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

        throw new AssertionError("Condition was not met within " + INGESTION_TIMEOUT);
    }

    private List<String> uniqueIndexFields(Class<?> documentType) {
        return mongoTemplate.indexOps(documentType)
                .getIndexInfo()
                .stream()
                .filter(IndexInfo::isUnique)
                .flatMap(index -> index.getIndexFields().stream())
                .map(field -> field.getKey())
                .toList();
    }

    private Map<String, Object> requestBody(String externalMessageId, String threadId) {
        return Map.of(
                "communicationType", "EMAIL",
                "sender", "alice.sharma@example-corp.test",
                "recipients", List.of(
                        "bob.patel@example-corp.test",
                        "carol.mehta@example-corp.test"
                ),
                "subject", "Project Atlas: budget review",
                "body", "Please review the latest budget information.",
                "messageTimestamp", "2026-09-07T18:30:00Z",
                "threadId", threadId,
                "externalMessageId", externalMessageId
        );
    }

    private Map<String, Object> attachment(String filename, String contentType, int size) {
        byte[] content = new byte[size];

        for (int index = 0; index < size; index++) {
            content[index] = (byte) (index % 251);
        }

        return Map.of(
                "filename", filename,
                "contentType", contentType,
                "contentBase64", Base64.getEncoder().encodeToString(content)
        );
    }

    private Consumer<String, String> createConsumer(String topic) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        Consumer<String, String> consumer = new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new StringDeserializer()
        );

        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofSeconds(2));

        return consumer;
    }

    private ConsumerRecord<String, String> pollForEvent(
            Consumer<String, String> consumer,
            String expectedKey
    ) {
        long deadline = System.currentTimeMillis() + INGESTION_TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

            for (ConsumerRecord<String, String> record : records) {
                if (expectedKey == null || expectedKey.equals(record.key())) {
                    return record;
                }
            }
        }

        return null;
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
