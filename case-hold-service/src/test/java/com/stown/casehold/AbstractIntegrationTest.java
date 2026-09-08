package com.stown.casehold;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Boots the application against real PostgreSQL and Kafka containers so that
 * the full pipeline — Flyway, JPA, service layer, transactional outbox and
 * Kafka publication — is exercised end to end.
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

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    protected com.stown.casehold.repository.OutboxEventRepository outboxEventRepository;

    @LocalServerPort
    protected int port;

    protected final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Keep the scheduled outbox sweep frequent enough for assertions.
        registry.add("app.case-hold.outbox.initial-delay-ms", () -> 1_000);
        registry.add("app.case-hold.outbox.interval-ms", () -> 1_000);
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
