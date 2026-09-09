package com.stown.search;

import com.stown.search.api.ReindexResponse;
import com.stown.search.api.SearchResponse;
import com.stown.search.api.SearchResultItem;
import com.stown.search.api.SearchStatsResponse;
import com.stown.search.domain.AttachmentMetadata;
import com.stown.search.domain.MessageDocument;
import com.stown.search.index.SearchDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end proof that a {@code message.ingested} event results in a
 * searchable Elasticsearch document keyed by messageId.
 *
 * <p>Containers are started once per JVM (singleton container pattern) because
 * the Spring test context is cached and shared across test classes; annotated
 * {@code @Container} statics would be restarted per class and break the cached
 * connections.
 *
 * <p>Tagged {@code integration} and therefore excluded from the default
 * surefire run: {@code mvn test -Dgroups=integration} runs it.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchIntegrationTest {

    private static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:7");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.2");
    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.0.3")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        MONGODB.start();
        KAFKA.start();
        ELASTICSEARCH.start();
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () -> MONGODB.getConnectionString() + "/legal_discovery");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add(
                "spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200)
        );
        registry.add("app.search.reconcile-interval-ms", () -> 3_000L);
    }

    @Test
    void indexesIngestedMessageAndMakesItSearchable() {
        String messageId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        mongoTemplate.save(MessageDocument.builder()
                .id(messageId)
                .deduplicationKey("b".repeat(64))
                .externalMessageId("<merger@example.com>")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Project Falcon merger agreement")
                .body("Attached is the signed merger agreement for Project Falcon.")
                .messageTimestamp(Instant.parse("2026-09-08T03:00:00Z"))
                .threadId("thread-falcon")
                .attachments(List.of(AttachmentMetadata.builder()
                        .attachmentId(UUID.randomUUID().toString())
                        .filename("merger-agreement.pdf")
                        .contentType("application/pdf")
                        .sizeBytes(2048L)
                        .build()))
                .createdAt(Instant.now())
                .holdCount(1)
                .dispositionStatus("RETAINED")
                .build());

        publishMessageIngested(eventId, messageId);

        SearchDocument indexed = awaitIndexedDocument(messageId);
        assertThat(indexed.getMessageId()).isEqualTo(messageId);
        assertThat(indexed.getSubject()).isEqualTo("Project Falcon merger agreement");
        assertThat(indexed.getAttachmentCount()).isEqualTo(1);
        assertThat(indexed.getAttachmentFilenames()).containsExactly("merger-agreement.pdf");
        assertThat(indexed.getHoldCount()).isEqualTo(1);
        assertThat(indexed.getDispositionStatus()).isEqualTo("RETAINED");

        SearchResponse response = awaitSearchHit(messageId);
        assertThat(response.total()).isGreaterThanOrEqualTo(1);
        assertThat(response.sort()).isEqualTo("relevance");
        assertThat(response.results()).anySatisfy(result -> {
            assertThat(result.messageId()).isEqualTo(messageId);
            assertThat(result.communicationType()).isEqualTo("EMAIL");
            assertThat(result.sender()).isEqualTo("alice@example.com");
            assertThat(result.snippet()).isNotBlank();
            assertThat(result.onHold()).isTrue();
            assertThat(result.dispositionStatus()).isEqualTo("RETAINED");
        });

        // The same message must survive the narrowing filters and be excluded by
        // their negation, which proves the filters reach Elasticsearch.
        assertThat(messageIds("/api/search?q=merger%20agreement"
                + "&onHold=true"
                + "&hasAttachments=true"
                + "&dispositionStatus=RETAINED"
                + "&recipient=bob@example.com"
                + "&after=2026-09-01T00:00:00Z"
                + "&before=2026-09-30T00:00:00Z"
                + "&sort=newest")).contains(messageId);

        assertThat(messageIds("/api/search?q=merger%20agreement&onHold=false")).doesNotContain(messageId);
        assertThat(messageIds("/api/search?q=merger%20agreement&after=2026-09-09T00:00:00Z"))
                .doesNotContain(messageId);
    }

    @Test
    void rejectsAnInvertedDateRange() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/search?q=merger&after=2026-09-08T00:00:00Z&before=2026-09-01T00:00:00Z"),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"after\"");
    }

    @Test
    void rejectsAnUnknownSort() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/search?q=merger&sort=subject"),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"sort\"");
    }

    @Test
    void rejectsSearchWithoutQuery() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/search"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"fieldErrors\"").contains("\"q\"");
    }

    /**
     * The disposition contract end to end: ingestion deletes the message from
     * MongoDB and publishes message.disposed, after which the document must
     * disappear from the index. Without this the subject and body stay
     * searchable after the record has been legally destroyed.
     */
    @Test
    void removesDisposedMessagesFromTheIndex() {
        String messageId = UUID.randomUUID().toString();

        mongoTemplate.save(MessageDocument.builder()
                .id(messageId)
                .deduplicationKey("c".repeat(64))
                .communicationType("EMAIL")
                .sender("dave@example.com")
                .recipients(List.of("erin@example.com"))
                .subject("Quarterly disposition candidate")
                .body("This message is scheduled for disposition after its retention period.")
                .messageTimestamp(Instant.parse("2026-09-08T03:00:00Z"))
                .createdAt(Instant.now())
                .build());

        publishMessageIngested(UUID.randomUUID().toString(), messageId);
        awaitIndexedDocument(messageId);

        // Ingestion removes the message before publishing the event.
        mongoTemplate.remove(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(messageId)
                ),
                MessageDocument.class
        );
        publishMessageDisposed(UUID.randomUUID().toString(), messageId, "RETENTION");

        awaitAbsentFromIndex(messageId);
        assertThat(messageIds("/api/search?q=disposition")).doesNotContain(messageId);
    }

    /**
     * Redelivery must be a no-op. The event is at-least-once, so a second
     * delivery of an already-applied disposition must not fail the listener and
     * push a valid event to the dead letter topic.
     */
    @Test
    void toleratesARedeliveredDispositionForAnAbsentDocument() {
        String messageId = UUID.randomUUID().toString();

        publishMessageDisposed(UUID.randomUUID().toString(), messageId, "MANUAL");
        publishMessageDisposed(UUID.randomUUID().toString(), messageId, "MANUAL");

        SearchStatsResponse stats = await(() -> {
            SearchStatsResponse body = restTemplate
                    .getForEntity(url("/api/search/stats"), SearchStatsResponse.class)
                    .getBody();
            return body == null ? null : body;
        }, "stats endpoint never responded");

        assertThat(stats.pendingFailures()).isZero();
    }

    /**
     * Reindex is the only path that can populate an index from messages that
     * were stored before this service ever saw them: they carry no pending
     * event, and the reconciliation back-fill deliberately looks only at the
     * newest batch.
     */
    @Test
    void reindexIndexesMessagesThatNoEventWillEverCover() {
        String messageId = UUID.randomUUID().toString();

        mongoTemplate.save(MessageDocument.builder()
                .id(messageId)
                .deduplicationKey("d".repeat(64))
                .communicationType("CHAT")
                .sender("frank@example.com")
                .recipients(List.of("grace@example.com"))
                .subject("Historic backfill candidate")
                .body("Stored directly, with no message.ingested event published for it.")
                .messageTimestamp(Instant.parse("2026-09-08T03:00:00Z"))
                .createdAt(Instant.now())
                .holdIds(List.of("hold-backfill-1"))
                .holdCount(1)
                .dispositionStatus("ON_HOLD")
                .build());

        ResponseEntity<ReindexResponse> response = restTemplate.postForEntity(
                url("/api/search/reindex"),
                null,
                ReindexResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().scanned()).isPositive();

        SearchDocument indexed = awaitIndexedDocument(messageId);
        assertThat(indexed.getSubject()).isEqualTo("Historic backfill candidate");
        assertThat(indexed.getHoldIds()).containsExactly("hold-backfill-1");

        // holdIds is mapped as a keyword array, so filtering by one hold works.
        assertThat(messageIds("/api/search?q=backfill&holdId=hold-backfill-1")).contains(messageId);
        assertThat(messageIds("/api/search?q=backfill&holdId=hold-does-not-exist")).doesNotContain(messageId);
        assertThat(messageIds("/api/search?q=backfill&onHold=true")).contains(messageId);
    }

    private List<String> messageIds(String path) {
        SearchResponse response = restTemplate.getForEntity(url(path), SearchResponse.class).getBody();
        assertThat(response).isNotNull();
        return response.results().stream().map(SearchResultItem::messageId).toList();
    }

    private void publishMessageDisposed(String eventId, String messageId, String reason) {
        String payload = """
                {"eventId":"%s","messageId":"%s","externalMessageId":"ext-%s",\
                "communicationType":"EMAIL","reason":"%s","attachmentsPurged":0,\
                "retentionUntil":"2026-09-08T03:00:00Z","disposedAt":"2026-09-09T03:00:00Z"}
                """.formatted(eventId, messageId, messageId, reason);

        publish("message.disposed", messageId, payload);
    }

    private void awaitAbsentFromIndex(String messageId) {
        await(() -> {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    url("/api/search/messages/" + messageId),
                    String.class
            );
            return response.getStatusCode() == HttpStatus.NOT_FOUND ? "gone" : null;
        }, "message " + messageId + " was still indexed after disposition");
    }

    private void publish(String topic, String key, String payload) {
        Map<String, Object> config = new HashMap<>();
        config.put(BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        config.put(VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

        KafkaTemplate<String, String> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        template.send(topic, key, payload);
        template.flush();
        template.destroy();
    }

    private void publishMessageIngested(String eventId, String messageId) {
        Map<String, Object> config = new HashMap<>();
        config.put(BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        config.put(VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

        KafkaTemplate<String, String> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        String payload = """
                {"eventId":"%s","messageId":"%s","deduplicationKey":"%s","occurredAt":"2026-09-08T03:00:00Z"}
                """.formatted(eventId, messageId, "b".repeat(64));

        template.send("message.ingested", messageId, payload);
        template.flush();
        template.destroy();
    }

    private SearchDocument awaitIndexedDocument(String messageId) {
        return await(() -> {
            ResponseEntity<SearchDocument> response = restTemplate.getForEntity(
                    url("/api/search/messages/" + messageId),
                    SearchDocument.class
            );
            return response.getStatusCode().is2xxSuccessful() ? response.getBody() : null;
        }, "message " + messageId + " was not indexed within the searchability budget");
    }

    private SearchResponse awaitSearchHit(String messageId) {
        return await(() -> {
            ResponseEntity<SearchResponse> response = restTemplate.getForEntity(
                    url("/api/search?q=merger%20agreement&communicationType=EMAIL"),
                    SearchResponse.class
            );

            SearchResponse body = response.getBody();
            if (body == null || body.results().stream().noneMatch(r -> messageId.equals(r.messageId()))) {
                return null;
            }
            return body;
        }, "message " + messageId + " was not searchable within the searchability budget");
    }

    private <T> T await(java.util.function.Supplier<T> supplier, String failureMessage) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));

        while (Instant.now().isBefore(deadline)) {
            try {
                T value = supplier.get();
                if (value != null) {
                    return value;
                }
            } catch (Exception ignored) {
                // keep polling until the deadline
            }

            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        throw new AssertionError(failureMessage);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
