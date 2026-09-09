package com.stown.exportaudit;

import com.stown.exportaudit.domain.AuditEventDocument;
import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportStatus;
import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.evidence.EvidenceProvider;
import com.stown.exportaudit.evidence.EvidenceQuery;
import com.stown.exportaudit.repository.AuditEventRepository;
import com.stown.exportaudit.repository.ExportJobRepository;
import com.stown.exportaudit.repository.MessageRepository;
import com.stown.exportaudit.service.AuditService;
import com.stown.exportaudit.service.ExportJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test that boots the application against real MongoDB, Kafka and
 * MinIO containers and exercises the full export and audit pipelines end to
 * end. Tagged {@code integration} and excluded from the default surefire run;
 * run with {@code -Dexcluded.test.groups=}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExportAuditIntegrationTest {

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

        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MONGODB.getConnectionString() + "/legal_discovery");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.storage.endpoint", MINIO::getS3URL);
        registry.add("app.storage.source-bucket", () -> "test-attachments");
        registry.add("app.storage.export-bucket", () -> "test-exports");
        registry.add("app.storage.region", () -> "us-east-1");
    }

    @Autowired
    private ExportJobService exportJobService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ExportJobRepository exportJobRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EvidenceProvider evidenceProvider;

    @BeforeEach
    void cleanUp() {
        exportJobRepository.deleteAll();
        auditEventRepository.deleteAll();
        messageRepository.deleteAll();
    }

    @Test
    void auditEventIsPersistedAppendOnly() {
        auditService.record(
                "HOLD_PLACED",
                "case-1",
                "HOLD",
                "hold-1",
                "compliance@example.com",
                "ACTIVE",
                Map.of("reason", "litigation")
        );

        List<AuditEventDocument> events = auditService.findByCaseId("case-1");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo("HOLD_PLACED");
        assertThat(events.get(0).getActor()).isEqualTo("compliance@example.com");
    }

    @Test
    void duplicateAuditEventIsIdempotent() {
        auditService.record(com.stown.exportaudit.messaging.AuditEvent.builder()
                .eventId("evt-dup-1")
                .caseId("case-1")
                .action("CASE_CREATED")
                .targetType("CASE")
                .targetId("case-1")
                .build());

        auditService.record(com.stown.exportaudit.messaging.AuditEvent.builder()
                .eventId("evt-dup-1")
                .caseId("case-1")
                .action("CASE_CREATED")
                .targetType("CASE")
                .targetId("case-1")
                .build());

        List<AuditEventDocument> events = auditService.findByCaseId("case-1");
        assertThat(events).hasSize(1);
    }

    @Test
    void evidenceProviderReadsMessagesFromMongo() {
        messageRepository.insert(MessageDocument.builder()
                .id("msg-1")
                .deduplicationKey("dedup-1")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Subject")
                .body("Body")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build());

        List<MessageDocument> messages = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .caseId("case-1")
                        .communicationType("EMAIL")
                        .build()
        );

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getId()).isEqualTo("msg-1");
    }

    @Test
    void exportJobLifecycleIsTrackedAndAudited() {
        // Insert evidence into MongoDB.
        messageRepository.insert(MessageDocument.builder()
                .id("msg-export-1")
                .deduplicationKey("dedup-export-1")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Export Subject")
                .body("Export body")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build());

        // Create the export job (synchronously triggers Kafka event + audit).
        ExportJobDocument job = exportJobService.createJob(
                "case-export-1",
                null,
                com.stown.exportaudit.domain.ExportScope.CASE,
                "investigator@example.com",
                "EMAIL",
                null,
                null,
                null,
                null
        );

        assertThat(job.getStatus()).isEqualTo(ExportStatus.QUEUED);

        // Wait for the worker to process the job asynchronously.
        await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    Optional<ExportJobDocument> updated = exportJobRepository.findById(job.getExportId());
                    assertThat(updated).isPresent();
                    assertThat(updated.get().getStatus()).isEqualTo(ExportStatus.COMPLETED);
                    assertThat(updated.get().getS3Key()).isNotNull();
                    assertThat(updated.get().getPackageSha256()).isNotBlank();
                });

        // Verify the audit events were recorded.
        List<AuditEventDocument> auditEvents = auditService.findByCaseId("case-export-1");
        assertThat(auditEvents).isNotEmpty();
        assertThat(auditEvents.stream()
                .anyMatch(e -> "EXPORT_REQUESTED".equals(e.getAction())))
                .isTrue();
        assertThat(auditEvents.stream()
                .anyMatch(e -> "EXPORT_COMPLETED".equals(e.getAction())))
                .isTrue();
    }
}
