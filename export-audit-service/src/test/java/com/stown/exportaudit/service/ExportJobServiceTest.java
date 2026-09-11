package com.stown.exportaudit.service;

import com.stown.exportaudit.config.ExportProperties;
import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.domain.ExportStatus;
import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.evidence.EvidenceProvider;
import com.stown.exportaudit.evidence.EvidenceQuery;
import com.stown.exportaudit.messaging.ExportCompletedEvent;
import com.stown.exportaudit.messaging.ExportRequestedEvent;
import com.stown.exportaudit.repository.ExportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportJobServiceTest {

    @Mock
    private ExportJobRepository exportJobRepository;
    @Mock
    private EvidenceProvider evidenceProvider;
    @Mock
    private PackageBuilder packageBuilder;
    @Mock
    private PackageVerifier packageVerifier;
    @Mock
    private S3StorageService storageService;
    @Mock
    private AuditService auditService;
    @Mock
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    private ExportProperties exportProperties;
    private ExportJobService exportJobService;

    @BeforeEach
    void setUp() {
        exportProperties = new ExportProperties();
        exportProperties.setTopic("export.requested");
        exportProperties.setCompletedTopic("export.completed");
        exportJobService = new ExportJobService(
                exportJobRepository,
                evidenceProvider,
                packageBuilder,
                packageVerifier,
                storageService,
                auditService,
                kafkaTemplate,
                exportProperties
        );
    }

    @Test
    void createJobPersistsQueuedJobAndPublishesEvent() {
        when(exportJobRepository.insert(any(ExportJobDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExportJobDocument job = exportJobService.createJob(
                "case-1",
                null,
                ExportScope.CASE,
                "investigator@example.com",
                "EMAIL",
                null,
                null,
                null,
                null
        );

        assertThat(job.getExportId()).isNotBlank();
        assertThat(job.getCaseId()).isEqualTo("case-1");
        assertThat(job.getScope()).isEqualTo(ExportScope.CASE);
        assertThat(job.getStatus()).isEqualTo(ExportStatus.QUEUED);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getCreatedAt()).isNotNull();

        verify(auditService).record(
                eq("EXPORT_REQUESTED"), eq("case-1"), eq("EXPORT"),
                anyString(), eq("investigator@example.com"), eq("QUEUED"), any()
        );
    }

    @Test
    void processSkipsAlreadyCompletedJob() {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .status(ExportStatus.COMPLETED)
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));

        exportJobService.process(ExportRequestedEvent.builder()
                .exportId("export-1")
                .eventId("evt-1")
                .build());

        verifyNoInteractions(evidenceProvider);
        verifyNoInteractions(packageBuilder);
        verify(storageService, never()).uploadPackage(anyString(), any());
    }

    @Test
    void processBuildsAndUploadsPackageOnSuccess() throws java.io.IOException {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .scope(ExportScope.CASE)
                .requestedBy("investigator@example.com")
                .status(ExportStatus.QUEUED)
                .attempts(0)
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));
        when(evidenceProvider.findEvidence(any(EvidenceQuery.class)))
                .thenReturn(List.of(MessageDocument.builder().id("msg-1").build()));

        PackageBuildResult result = new PackageBuildResult(
                new byte[]{1, 2, 3},
                new com.stown.exportaudit.domain.ExportManifest(
                        "export-1", "case-1", "CASE", "investigator@example.com",
                        Instant.now(), "pkg-sha", 1, 0, List.of()
                ),
                "pkg-sha"
        );
        when(packageBuilder.build(any(), any()))
                .thenReturn(result);
        when(storageService.exportKey("export-1", "export-package.zip"))
                .thenReturn("exports/export-1/export-package.zip");
        when(storageService.getExportBucket()).thenReturn("export-bucket");

        exportJobService.process(ExportRequestedEvent.builder()
                .exportId("export-1")
                .caseId("case-1")
                .scope("CASE")
                .requestedBy("investigator@example.com")
                .eventId("evt-1")
                .build());

        ArgumentCaptor<ExportJobDocument> jobCaptor = ArgumentCaptor.forClass(ExportJobDocument.class);
        verify(exportJobRepository, atLeastOnce()).save(jobCaptor.capture());

        ExportJobDocument saved = jobCaptor.getAllValues().stream()
                .filter(j -> j.getStatus() == ExportStatus.COMPLETED)
                .findFirst().orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(ExportStatus.COMPLETED);
        assertThat(saved.getS3Bucket()).isEqualTo("export-bucket");
        assertThat(saved.getS3Key()).isEqualTo("exports/export-1/export-package.zip");
        assertThat(saved.getPackageSha256()).isEqualTo("pkg-sha");
        assertThat(saved.getMessageCount()).isEqualTo(1);
        assertThat(saved.getAttempts()).isEqualTo(1);

        verify(storageService).uploadPackage("exports/export-1/export-package.zip", new byte[]{1, 2, 3});
        verify(auditService).record(eq("EXPORT_COMPLETED"), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void processMarksFailedAndRethrowsOnError() throws java.io.IOException {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .scope(ExportScope.CASE)
                .requestedBy("investigator@example.com")
                .status(ExportStatus.QUEUED)
                .attempts(0)
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));
        when(evidenceProvider.findEvidence(any()))
                .thenReturn(List.of());
        when(packageBuilder.build(any(), any()))
                .thenThrow(new ExportProcessingException("boom", new RuntimeException("cause")));

        assertThatThrownBy(() -> exportJobService.process(ExportRequestedEvent.builder()
                .exportId("export-1")
                .caseId("case-1")
                .scope("CASE")
                .requestedBy("investigator@example.com")
                .eventId("evt-1")
                .build()))
                .isInstanceOf(ExportProcessingException.class);

        ArgumentCaptor<ExportJobDocument> jobCaptor = ArgumentCaptor.forClass(ExportJobDocument.class);
        verify(exportJobRepository, atLeastOnce()).save(jobCaptor.capture());

        ExportJobDocument saved = jobCaptor.getAllValues().stream()
                .filter(j -> j.getStatus() == ExportStatus.FAILED)
                .findFirst().orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(saved.getLastError()).contains("ExportProcessingException");

        verify(storageService, never()).uploadPackage(anyString(), any());
        verify(auditService).record(eq("EXPORT_FAILED"), anyString(), anyString(), anyString(), anyString(), eq("FAILED"), any());
    }

    @Test
    void retryRejectsNonFailedJob() {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .status(ExportStatus.COMPLETED)
                .requestedBy("investigator@example.com")
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> exportJobService.retry("export-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only FAILED jobs can be retried");
    }

    @Test
    void retryRequeuesFailedJob() {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .status(ExportStatus.FAILED)
                .requestedBy("investigator@example.com")
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));

        ExportJobDocument result = exportJobService.retry("export-1");

        assertThat(result.getStatus()).isEqualTo(ExportStatus.QUEUED);
        assertThat(result.getLastError()).isNull();
        verify(auditService).record(eq("EXPORT_RETRY_REQUESTED"), anyString(), anyString(), anyString(), anyString(), eq("QUEUED"), any());
    }

    @Test
    void retryThrowsWhenJobNotFound() {
        when(exportJobRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exportJobService.retry("missing"))
                .isInstanceOf(ExportJobNotFoundException.class);
    }

    @Test
    void getDownloadUrlReturnsPresignedUrlAndAudits() {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .status(ExportStatus.COMPLETED)
                .s3Key("exports/export-1/export-package.zip")
                .requestedBy("investigator@example.com")
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));
        when(storageService.packageExists("exports/export-1/export-package.zip")).thenReturn(true);
        when(storageService.presignedDownloadUrl("exports/export-1/export-package.zip"))
                .thenReturn("https://presigned-url");

        String url = exportJobService.getDownloadUrl("export-1");

        assertThat(url).isEqualTo("https://presigned-url");
        verify(auditService).record(eq("EXPORT_DOWNLOADED"), anyString(), anyString(), anyString(), anyString(), eq("DOWNLOADED"), any());
    }

    @Test
    void getDownloadUrlRejectsIncompleteJob() {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .status(ExportStatus.RUNNING)
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> exportJobService.getDownloadUrl("export-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready for download");
    }

    @Test
    void verifyRecomputesAndComparesChecksum() throws java.io.IOException {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .status(ExportStatus.COMPLETED)
                .s3Key("exports/export-1/export-package.zip")
                .packageSha256("recorded-sha")
                .requestedBy("investigator@example.com")
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));
        when(storageService.readAttachment("export-bucket", "exports/export-1/export-package.zip"))
                .thenReturn(new byte[]{1, 2, 3});
        when(storageService.getExportBucket()).thenReturn("export-bucket");
        when(packageVerifier.verify("export-1", new byte[]{1, 2, 3}, "recorded-sha"))
                .thenReturn(new PackageVerification(
                        "export-1", true, "recorded-sha", "recorded-sha",
                        List.of(), true
                ));

        PackageVerification verification = exportJobService.verify("export-1");

        assertThat(verification.verified()).isTrue();
        assertThat(verification.packageChecksumMatches()).isTrue();
        verify(auditService).record(eq("EXPORT_VERIFIED"), anyString(), anyString(), anyString(), anyString(), eq("VERIFIED"), any());
    }

    @Test
    void verifyDetectsChecksumMismatch() throws java.io.IOException {
        ExportJobDocument job = ExportJobDocument.builder()
                .exportId("export-1")
                .caseId("case-1")
                .status(ExportStatus.COMPLETED)
                .s3Key("exports/export-1/export-package.zip")
                .packageSha256("recorded-sha")
                .requestedBy("investigator@example.com")
                .build();

        when(exportJobRepository.findById("export-1")).thenReturn(Optional.of(job));
        when(storageService.readAttachment("export-bucket", "exports/export-1/export-package.zip"))
                .thenReturn(new byte[]{1, 2, 3});
        when(storageService.getExportBucket()).thenReturn("export-bucket");
        when(packageVerifier.verify("export-1", new byte[]{1, 2, 3}, "recorded-sha"))
                .thenReturn(new PackageVerification(
                        "export-1", false, "recorded-sha", "different-sha",
                        List.of(), false
                ));

        PackageVerification verification = exportJobService.verify("export-1");

        assertThat(verification.verified()).isFalse();
        assertThat(verification.packageChecksumMatches()).isFalse();
        verify(auditService).record(eq("EXPORT_VERIFIED"), anyString(), anyString(), anyString(), anyString(), eq("MISMATCH"), any());
    }
}
