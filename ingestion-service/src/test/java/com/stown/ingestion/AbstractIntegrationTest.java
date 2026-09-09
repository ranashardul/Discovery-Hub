package com.stown.ingestion;

import com.stown.ingestion.repository.IngestionRequestRepository;
import com.stown.ingestion.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Boots the application against real MongoDB, Kafka and MinIO containers so
 * that the full asynchronous pipeline is exercised end to end.
 *
 * <p>The containers are started once per JVM (singleton container pattern)
 * because the Spring test context is cached and shared across test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String TEST_BUCKET = "test-attachments";

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    private static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:7");
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.2");
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest")
            .withUserName(ACCESS_KEY)
            .withPassword(SECRET_KEY);

    static {
        MONGODB.start();
        KAFKA.start();
        MINIO.start();

        // Picked up by the AWS SDK default credentials chain.
        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
    }

    @Autowired
    protected MessageRepository messageRepository;

    @Autowired
    protected IngestionRequestRepository ingestionRequestRepository;

    @LocalServerPort
    protected int port;

    protected final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.mongodb.uri",
                () -> MONGODB.getConnectionString() + "/legal_discovery"
        );
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.bucket", () -> TEST_BUCKET);
        registry.add("app.storage.region", () -> "us-east-1");

        // Keep the scheduled outbox sweep out of the way of assertions.
        registry.add("app.outbox.initial-delay-ms", () -> 2000);
        registry.add("app.outbox.interval-ms", () -> 2000);

        // Retention tests drive disposition explicitly, so the scheduled job
        // is disabled to keep runs deterministic. Short periods are permitted
        // because that is exactly what is under test.
        registry.add("app.retention.enabled", () -> false);
        registry.add("app.retention.allow-short-retention", () -> true);
        registry.add("app.retention.periods.EMAIL", () -> "2s");
        registry.add("app.retention.periods.CHAT", () -> "2s");
        registry.add("app.retention.default-period", () -> "2s");
    }

    protected static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    protected static String minioUrl() {
        return MINIO.getS3URL();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void clearCollections() {
        messageRepository.deleteAll();
        ingestionRequestRepository.deleteAll();
    }
}
