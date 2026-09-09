package com.stown.exportaudit;

import com.stown.exportaudit.domain.AuditEventDocument;
import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportStatus;
import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.evidence.EvidenceProvider;
import com.stown.exportaudit.evidence.EvidenceQuery;
import com.stown.exportaudit.repository.AuditEventRepository;
import com.stown.exportaudit.repository.ExportJobRepository;
import com.stown.exportaudit.service.AuditService;
import com.stown.exportaudit.service.CaseHoldClient;
import com.stown.exportaudit.service.ExportJobService;
import com.stown.exportaudit.service.IngestionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test that boots the application against real MongoDB, Kafka and
 * MinIO containers and exercises the export and audit pipelines end to end.
 *
 * <p>The export service's own data (the {@code export_jobs} and
 * {@code audit_events} collections) is read and written against real MongoDB.
 * The two external dependencies the export service talks to over HTTP — the
 * Case & Hold service (evidence IDs) and the ingestion service (message
 * content) — are stubbed at their client boundary, because those services are
 * not started here. This is exactly the shape NFR-1 mandates: each service owns
 * its data, and the export service reaches message content through the
 * ingestion API rather than the shared {@code messages} MongoDB collection.
 *
 * <p>Tagged {@code integration} and excluded from the default surefire run; run
 * with {@code -Dexcluded.test.groups=}.
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
    private EvidenceProvider evidenceProvider;

    @Autowired
    private IngestionClientStub ingestionClientStub;

    @BeforeEach
    void cleanUp() {
        exportJobRepository.deleteAll();
        auditEventRepository.deleteAll();
        ingestionClientStub.clear();
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
    void evidenceProviderReadsMessagesViaIngestionApi() {
        ingestionClientStub.setMessages(List.of(MessageDocument.builder()
                .id("msg-1")
                .deduplicationKey("dedup-1")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Subject")
                .body("Body")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build()));

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
        // Provide the evidence the ingestion service would have returned.
        ingestionClientStub.setMessages(List.of(MessageDocument.builder()
                .id("msg-export-1")
                .deduplicationKey("dedup-export-1")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com"))
                .subject("Export Subject")
                .body("Export body")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build()));

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

    /**
     * Stub for the ingestion service read API. The real service is not started
     * in this test, so message content is supplied here at the HTTP boundary
     * the export service actually calls. The export service's own MongoDB
     * collections are still exercised for real above.
     */
    static class IngestionClientStub extends IngestionClient {
        private volatile List<MessageDocument> messages = List.of();

        IngestionClientStub() {
            super(null, null);
        }

        @Override
        public List<MessageDocument> getMessages(Set<String> ids) {
            return List.copyOf(messages);
        }

        void setMessages(List<MessageDocument> messages) {
            this.messages = messages == null ? List.of() : messages;
        }

        void clear() {
            this.messages = List.of();
        }
    }

    /**
     * Replaces the two HTTP clients the export service uses to reach the Case
     * & Hold and ingestion services, which are not started in this test.
     * The beans are given distinct names (not the @Service bean names) and
     * marked @Primary so they win injection over the real HTTP clients, which
     * avoids bean-definition override conflicts while keeping the export
     * service's own MongoDB collections real. The Case & Hold stub returns the
     * ids of whatever messages the ingestion stub is holding so the two stay
     * consistent for a case export.
     */
    @TestConfiguration
    static class ExternalClientStubsConfig {

        @Bean
        @Primary
        IngestionClientStub ingestionClientStub() {
            return new IngestionClientStub();
        }

        @Bean
        @Primary
        CaseHoldClient caseHoldClientStub(IngestionClientStub ingestionClientStub) {
            return new CaseHoldClient(null, null) {
                @Override
                public List<String> getCaseCommunicationIds(String caseId) {
                    return ingestionClientStub.getMessages(null).stream()
                            .map(MessageDocument::getId)
                            .toList();
                }

                @Override
                public List<String> getHoldCommunicationIds(String holdId) {
                    return ingestionClientStub.getMessages(null).stream()
                            .map(MessageDocument::getId)
                            .toList();
                }
            };
        }
    }
}
