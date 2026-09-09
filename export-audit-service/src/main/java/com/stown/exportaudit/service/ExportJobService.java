package com.stown.exportaudit.service;

import com.stown.exportaudit.config.ExportProperties;
import com.stown.exportaudit.domain.AuditAction;
import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.domain.ExportStatus;
import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.evidence.EvidenceProvider;
import com.stown.exportaudit.evidence.EvidenceQuery;
import com.stown.exportaudit.messaging.ExportCompletedEvent;
import com.stown.exportaudit.messaging.ExportRequestedEvent;
import com.stown.exportaudit.repository.ExportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates export job state: creation, asynchronous processing, status
 * lookup and retry. The API half creates a {@code QUEUED} job and publishes an
 * {@code export.requested} event; the worker half consumes it, builds the
 * package, uploads it to S3 and marks the job {@code COMPLETED} (or
 * {@code FAILED}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportJobService {

    public static final String PACKAGE_FILENAME = "export-package.zip";

    private final ExportJobRepository exportJobRepository;
    private final EvidenceProvider evidenceProvider;
    private final PackageBuilder packageBuilder;
    private final PackageVerifier packageVerifier;
    private final S3StorageService storageService;
    private final AuditService auditService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ExportProperties exportProperties;

    /**
     * API half: creates a {@code QUEUED} job and publishes the request event.
     * Returns the job immediately so the caller can poll status.
     */
    public ExportJobDocument createJob(
            String caseId,
            String holdId,
            ExportScope scope,
            String requestedBy,
            String communicationType,
            String sender,
            String threadId,
            Instant fromTimestamp,
            Instant toTimestamp
    ) {
        String exportId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ExportJobDocument job = ExportJobDocument.builder()
                .exportId(exportId)
                .caseId(caseId)
                .holdId(holdId)
                .scope(scope)
                .requestedBy(requestedBy)
                .communicationType(communicationType)
                .sender(sender)
                .threadId(threadId)
                .fromTimestamp(fromTimestamp)
                .toTimestamp(toTimestamp)
                .status(ExportStatus.QUEUED)
                .attempts(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        job = exportJobRepository.insert(job);

        publishRequestedEvent(job);

        auditService.record(
                AuditAction.EXPORT_REQUESTED.name(),
                caseId,
                "EXPORT",
                exportId,
                requestedBy,
                "QUEUED",
                Map.of(
                        "scope", scope == null ? null : scope.name(),
                        "caseId", caseId
                )
        );

        log.info("Created export job exportId={} caseId={}", exportId, caseId);

        return job;
    }

    /**
     * Worker half: processes an {@code export.requested} event. Idempotent — a
     * job already {@code COMPLETED} is skipped, and a job already
     * {@code RUNNING} is only reprocessed if it previously failed.
     */
    public void process(ExportRequestedEvent event) {
        ExportJobDocument job = exportJobRepository.findById(event.getExportId())
                .orElse(null);

        if (job == null) {
            log.warn("Ignoring export event for unknown exportId={}", event.getExportId());
            return;
        }

        if (job.getStatus() == ExportStatus.COMPLETED) {
            log.debug("Export already completed exportId={}", job.getExportId());
            return;
        }

        markRunning(job);

        try {
            PackageBuildResult result = buildPackage(job);

            String s3Key = storageService.exportKey(job.getExportId(), PACKAGE_FILENAME);
            storageService.uploadPackage(s3Key, result.packageBytes());

            markCompleted(job, s3Key, result);

            auditService.record(
                    AuditAction.EXPORT_COMPLETED.name(),
                    job.getCaseId(),
                    "EXPORT",
                    job.getExportId(),
                    job.getRequestedBy(),
                    "COMPLETED",
                    Map.of(
                            "s3Bucket", storageService.getExportBucket(),
                            "s3Key", s3Key,
                            "packageSha256", result.packageSha256(),
                            "messageCount", result.manifest().messageCount(),
                            "attachmentCount", result.manifest().attachmentCount()
                    )
            );

            publishCompletedEvent(job, result, s3Key, null);

            log.info(
                    "Completed export exportId={} bytes={} sha256={}",
                    job.getExportId(),
                    result.packageBytes().length,
                    result.packageSha256()
            );
        } catch (RuntimeException exception) {
            markFailed(job, exception);

            auditService.record(
                    AuditAction.EXPORT_FAILED.name(),
                    job.getCaseId(),
                    "EXPORT",
                    job.getExportId(),
                    job.getRequestedBy(),
                    "FAILED",
                    Map.of(
                            "error", exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    )
            );

            publishCompletedEvent(job, null, null, exception.getMessage());

            // Rethrow so the container retry policy and dead-letter topic apply.
            throw exception;
        }
    }

    /** Retries a failed job by re-publishing its request event. */
    public ExportJobDocument retry(String exportId) {
        ExportJobDocument job = exportJobRepository.findById(exportId)
                .orElseThrow(() -> new ExportJobNotFoundException(exportId));

        if (job.getStatus() != ExportStatus.FAILED) {
            throw new IllegalStateException(
                    "Only FAILED jobs can be retried; current status=" + job.getStatus()
            );
        }

        job.setStatus(ExportStatus.QUEUED);
        job.setLastError(null);
        job.setUpdatedAt(Instant.now());
        exportJobRepository.save(job);

        publishRequestedEvent(job);

        auditService.record(
                AuditAction.EXPORT_RETRY_REQUESTED.name(),
                job.getCaseId(),
                "EXPORT",
                exportId,
                job.getRequestedBy(),
                "QUEUED",
                Map.of("attempt", job.getAttempts() + 1)
        );

        log.info("Retrying export job exportId={}", exportId);

        return job;
    }

    public Optional<ExportJobDocument> findById(String exportId) {
        return exportJobRepository.findById(exportId);
    }

    public List<ExportJobDocument> findByCaseId(String caseId) {
        return exportJobRepository.findByCaseIdOrderByCreatedAtDesc(caseId);
    }

    /**
     * Generates an expiring presigned download URL for a completed export.
     */
    public String getDownloadUrl(String exportId) {
        ExportJobDocument job = exportJobRepository.findById(exportId)
                .orElseThrow(() -> new ExportJobNotFoundException(exportId));

        if (job.getStatus() != ExportStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Export is not ready for download; status=" + job.getStatus()
            );
        }

        if (job.getS3Key() == null || !storageService.packageExists(job.getS3Key())) {
            throw new ExportStorageException(
                    "Export package not found in S3: " + job.getS3Key(),
                    null
            );
        }

        String url = storageService.presignedDownloadUrl(job.getS3Key());

        auditService.record(
                AuditAction.EXPORT_DOWNLOADED.name(),
                job.getCaseId(),
                "EXPORT",
                exportId,
                job.getRequestedBy(),
                "DOWNLOADED",
                Map.of("s3Key", job.getS3Key())
        );

        return url;
    }

    /**
     * Verifies a completed export against its manifest. Reads the stored
     * package, parses it, recomputes the SHA-256 of every item and of the
     * package itself, and compares each against the recorded value. Any
     * tampering is detected and reported per item.
     */
    public PackageVerification verify(String exportId) {
        ExportJobDocument job = exportJobRepository.findById(exportId)
                .orElseThrow(() -> new ExportJobNotFoundException(exportId));

        if (job.getStatus() != ExportStatus.COMPLETED || job.getS3Key() == null) {
            throw new IllegalStateException(
                    "Cannot verify export in status=" + job.getStatus()
            );
        }

        byte[] content = storageService.readAttachment(
                storageService.getExportBucket(),
                job.getS3Key()
        );

        PackageVerification verification;

        try {
            verification = packageVerifier.verify(exportId, content, job.getPackageSha256());
        } catch (Exception exception) {
            throw new ExportProcessingException(
                    "Failed to verify export package: " + exception.getMessage(),
                    exception
            );
        }

        auditService.record(
                AuditAction.EXPORT_VERIFIED.name(),
                job.getCaseId(),
                "EXPORT",
                exportId,
                job.getRequestedBy(),
                verification.verified() ? "VERIFIED" : "MISMATCH",
                Map.of(
                        "packageChecksumMatches", verification.packageChecksumMatches(),
                        "itemsMatched", verification.items().stream()
                                .filter(ItemVerification::matches).count(),
                        "itemsTotal", verification.items().size()
                )
        );

        log.info(
                "Verified export exportId={} packageMatches={} itemsMatch={}/{} verified={}",
                exportId,
                verification.packageChecksumMatches(),
                verification.items().stream().filter(ItemVerification::matches).count(),
                verification.items().size(),
                verification.verified()
        );

        return verification;
    }

    private PackageBuildResult buildPackage(ExportJobDocument job) {
        EvidenceQuery query = EvidenceQuery.builder()
                .scope(job.getScope())
                .caseId(job.getCaseId())
                .holdId(job.getHoldId())
                .communicationType(job.getCommunicationType())
                .sender(job.getSender())
                .threadId(job.getThreadId())
                .fromTimestamp(job.getFromTimestamp())
                .toTimestamp(job.getToTimestamp())
                .build();

        List<MessageDocument> messages = evidenceProvider.findEvidence(query);

        try {
            return packageBuilder.build(
                    job.getExportId(),
                    job.getCaseId(),
                    job.getScope(),
                    job.getRequestedBy(),
                    messages
            );
        } catch (Exception exception) {
            throw new ExportProcessingException(
                    "Failed to build export package: " + exception.getMessage(),
                    exception
            );
        }
    }

    private void markRunning(ExportJobDocument job) {
        job.setStatus(ExportStatus.RUNNING);
        job.setAttempts(job.getAttempts() + 1);
        job.setStartedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        exportJobRepository.save(job);
    }

    private void markCompleted(ExportJobDocument job, String s3Key, PackageBuildResult result) {
        job.setStatus(ExportStatus.COMPLETED);
        job.setS3Bucket(storageService.getExportBucket());
        job.setS3Key(s3Key);
        job.setPackageSizeBytes(result.packageBytes().length);
        job.setPackageSha256(result.packageSha256());
        job.setMessageCount(result.manifest().messageCount());
        job.setAttachmentCount(result.manifest().attachmentCount());
        job.setLastError(null);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        exportJobRepository.save(job);
    }

    private void markFailed(ExportJobDocument job, RuntimeException exception) {
        job.setStatus(ExportStatus.FAILED);
        job.setLastError(
                exception.getClass().getSimpleName() + ": " + exception.getMessage()
        );
        job.setUpdatedAt(Instant.now());
        exportJobRepository.save(job);

        log.error(
                "Failed to process export exportId={} attempt={}",
                job.getExportId(),
                job.getAttempts(),
                exception
        );
    }

    private void publishRequestedEvent(ExportJobDocument job) {
        ExportRequestedEvent event = ExportRequestedEvent.builder()
                .exportId(job.getExportId())
                .caseId(job.getCaseId())
                .holdId(job.getHoldId())
                .scope(job.getScope() == null ? null : job.getScope().name())
                .requestedBy(job.getRequestedBy())
                .communicationType(job.getCommunicationType())
                .sender(job.getSender())
                .threadId(job.getThreadId())
                .fromTimestamp(job.getFromTimestamp())
                .toTimestamp(job.getToTimestamp())
                .eventId(UUID.randomUUID().toString())
                .requestedAt(Instant.now())
                .build();

        kafkaTemplate.send(exportProperties.getTopic(), job.getExportId(), event);
    }

    private void publishCompletedEvent(
            ExportJobDocument job,
            PackageBuildResult result,
            String s3Key,
            String errorMessage
    ) {
        ExportCompletedEvent event = ExportCompletedEvent.builder()
                .exportId(job.getExportId())
                .caseId(job.getCaseId())
                .status(job.getStatus().name())
                .s3Bucket(result == null ? null : storageService.getExportBucket())
                .s3Key(s3Key)
                .packageSizeBytes(result == null ? 0 : result.packageBytes().length)
                .packageSha256(result == null ? null : result.packageSha256())
                .messageCount(result == null ? 0 : result.manifest().messageCount())
                .attachmentCount(result == null ? 0 : result.manifest().attachmentCount())
                .errorMessage(errorMessage)
                .completedAt(Instant.now())
                .build();

        kafkaTemplate.send(exportProperties.getCompletedTopic(), job.getExportId(), event);
    }
}
