package com.stown.search;

import com.stown.search.api.SearchResponse;
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
                .build());

        publishMessageIngested(eventId, messageId);

        SearchDocument indexed = awaitIndexedDocument(messageId);
        assertThat(indexed.getMessageId()).isEqualTo(messageId);
        assertThat(indexed.getSubject()).isEqualTo("Project Falcon merger agreement");
        assertThat(indexed.getAttachmentCount()).isEqualTo(1);
        assertThat(indexed.getAttachmentFilenames()).containsExactly("merger-agreement.pdf");

        SearchResponse response = awaitSearchHit(messageId);
        assertThat(response.total()).isGreaterThanOrEqualTo(1);
        assertThat(response.results()).anySatisfy(result -> {
            assertThat(result.messageId()).isEqualTo(messageId);
            assertThat(result.communicationType()).isEqualTo("EMAIL");
            assertThat(result.sender()).isEqualTo("alice@example.com");
            assertThat(result.snippet()).isNotBlank();
        });
    }

    @Test
    void rejectsSearchWithoutQuery() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/search"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"fieldErrors\"").contains("\"q\"");
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
