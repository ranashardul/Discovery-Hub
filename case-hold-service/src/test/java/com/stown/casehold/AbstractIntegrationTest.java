package com.stown.casehold;

import com.stown.casehold.domain.AttachmentMetadata;
import com.stown.casehold.domain.MessageDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;

/**
 * Boots the application against real PostgreSQL, MongoDB and Kafka containers
 * so that the full pipeline — Flyway, JPA, the read-only message projection,
 * the service layer, the transactional outbox and Kafka publication — is
 * exercised end to end.
 *
 * <p>Containers are started once per JVM (singleton container pattern) because
 * the Spring test context is cached and shared across test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("case_hold")
                    .withUsername("casehold")
                    .withPassword("casehold");

    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.2");

    private static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:7");

    static {
        POSTGRES.start();
        KAFKA.start();
        MONGODB.start();
    }

    @Autowired
    protected com.stown.casehold.repository.OutboxEventRepository outboxEventRepository;

    @Autowired
    protected MongoTemplate mongoTemplate;

    @LocalServerPort
    protected int port;

    protected final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.mongodb.uri", () -> MONGODB.getConnectionString() + "/legal_discovery");

        // Keep the scheduled outbox sweep frequent enough for assertions.
        registry.add("app.case-hold.outbox.initial-delay-ms", () -> 1_000);
        registry.add("app.case-hold.outbox.interval-ms", () -> 1_000);
    }

    /**
     * Seeds a message in the shape ingestion-service writes, so reference
     * resolution is asserted against a realistic document rather than a stub.
     */
    protected MessageDocument seedMessage(
            String messageId,
            String sender,
            String subject,
            int holdCount
    ) {
        return mongoTemplate.save(MessageDocument.builder()
                .id(messageId)
                .communicationType("EMAIL")
                .sender(sender)
                .recipients(List.of("arjun.mehta@example-bank.test"))
                .subject(subject)
                .messageTimestamp(Instant.parse("2026-03-15T09:22:00Z"))
                .threadId("thread-" + messageId)
                .attachments(List.of(AttachmentMetadata.builder()
                        .attachmentId("att-1")
                        .filename("credit-memo.pdf")
                        .contentType("application/pdf")
                        .sizeBytes(2048L)
                        .build()))
                .createdAt(Instant.now())
                .retentionUntil(Instant.parse("2027-03-15T09:22:00Z"))
                .holdIds(holdCount > 0 ? List.of("hold-" + messageId) : List.of())
                .holdCount(holdCount)
                .dispositionStatus(holdCount > 0 ? "ON_HOLD" : "ACTIVE")
                .build());
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
