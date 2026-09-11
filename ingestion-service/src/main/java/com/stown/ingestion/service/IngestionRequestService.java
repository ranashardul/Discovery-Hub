package com.stown.ingestion.service;

import com.stown.ingestion.api.AttachmentRequest;
import com.stown.ingestion.api.IngestionRequest;
import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.StagedAttachment;
import com.stown.ingestion.messaging.IngestionRequestedEvent;
import com.stown.ingestion.repository.IngestionRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API-side half of ingestion. It validates, registers and publishes the
 * request, then returns immediately. Durable message storage is performed by
 * {@code IngestionWorker}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionRequestService {

    public static final String INGESTION_REQUESTED_TOPIC = "ingestion.requested";

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final IngestionRequestRepository requestRepository;
    private final HashService hashService;
    private final S3StorageService storageService;
    private final KafkaTemplate<String, IngestionRequestedEvent> kafkaTemplate;
    private final RetentionPolicy retentionPolicy;

    public record AcceptedRequest(IngestionRequestDocument request, boolean duplicate) {
    }

    public AcceptedRequest accept(IngestionRequest request) {
        // Rejected before anything is staged or published: an unusable
        // retention request should cost nothing and name its own field,
        // rather than failing later in the worker where the caller cannot
        // see it.
        retentionPolicy.messageOverride(request.getRetentionMinutes());

        String deduplicationKey = hashService.calculateDeduplicationKey(request);

        Optional<IngestionRequestDocument> existing = findExisting(request, deduplicationKey);

        if (existing.isPresent()) {
            log.info(
                    "Duplicate ingestion request deduplicationKey={} status={} messageId={}",
                    deduplicationKey,
                    existing.get().getStatus(),
                    existing.get().getMessageId()
            );

            return new AcceptedRequest(existing.get(), true);
        }

        String requestId = UUID.randomUUID().toString();
        List<StagedAttachment> staged = stageAttachments(requestId, request.getAttachments());

        Instant now = Instant.now();

        IngestionRequestDocument document = IngestionRequestDocument.builder()
                .requestId(requestId)
                .deduplicationKey(deduplicationKey)
                .externalMessageId(blankToNull(request.getExternalMessageId()))
                .status(IngestionStatus.RECEIVED)
                .attempts(0)
                .stagedAttachments(staged)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            document = requestRepository.insert(document);
        } catch (DuplicateKeyException exception) {
            // A concurrent request registered first. Drop the staged objects and
            // return the winner so the caller still sees a stable identity.
            staged.forEach(attachment -> deleteQuietly(attachment.getStagingKey()));

            IngestionRequestDocument winner = findExisting(request, deduplicationKey)
                    .orElseThrow(() -> exception);

            log.info(
                    "Concurrent duplicate request resolved requestId={} deduplicationKey={}",
                    winner.getRequestId(),
                    deduplicationKey
            );

            return new AcceptedRequest(winner, true);
        }

        publishRequestedEvent(request, document);

        log.info(
                "Accepted ingestion request requestId={} deduplicationKey={} attachments={}",
                document.getRequestId(),
                deduplicationKey,
                staged.size()
        );

        return new AcceptedRequest(document, false);
    }

    public Optional<IngestionRequestDocument> findByRequestId(String requestId) {
        return requestRepository.findById(requestId);
    }

    public Optional<IngestionRequestDocument> findByDeduplicationKey(String deduplicationKey) {
        return requestRepository.findByDeduplicationKey(deduplicationKey);
    }

    public Optional<IngestionRequestDocument> findByExternalMessageId(String externalMessageId) {
        return requestRepository.findByExternalMessageId(externalMessageId);
    }

    private Optional<IngestionRequestDocument> findExisting(
            IngestionRequest request,
            String deduplicationKey
    ) {
        Optional<IngestionRequestDocument> byKey =
                requestRepository.findByDeduplicationKey(deduplicationKey);

        if (byKey.isPresent()) {
            return byKey;
        }

        String externalMessageId = blankToNull(request.getExternalMessageId());

        return externalMessageId == null
                ? Optional.empty()
                : requestRepository.findByExternalMessageId(externalMessageId);
    }

    private List<StagedAttachment> stageAttachments(
            String requestId,
            List<AttachmentRequest> attachments
    ) {
        List<StagedAttachment> staged = new ArrayList<>();

        if (attachments == null || attachments.isEmpty()) {
            return staged;
        }

        try {
            for (int index = 0; index < attachments.size(); index++) {
                AttachmentRequest attachment = attachments.get(index);
                byte[] content = decode(attachment);
                String key = storageService.stagingKey(
                        requestId,
                        index,
                        attachment.getFilename()
                );
                String contentType = attachment.getContentType() == null
                        || attachment.getContentType().isBlank()
                        ? DEFAULT_CONTENT_TYPE
                        : attachment.getContentType();

                storageService.upload(key, content, contentType);

                staged.add(StagedAttachment.builder()
                        .filename(attachment.getFilename())
                        .contentType(contentType)
                        .sizeBytes(content.length)
                        .sha256(storageService.sha256(content))
                        .stagingBucket(storageService.getBucket())
                        .stagingKey(key)
                        .build());
            }
        } catch (RuntimeException exception) {
            staged.forEach(attachment -> deleteQuietly(attachment.getStagingKey()));
            throw exception;
        }

        return staged;
    }

    private byte[] decode(AttachmentRequest attachment) {
        try {
            return Base64.getDecoder().decode(attachment.getContentBase64());
        } catch (IllegalArgumentException exception) {
            throw new InvalidAttachmentException(
                    "Attachment %s does not contain valid base64 content"
                            .formatted(attachment.getFilename())
            );
        }
    }

    private void publishRequestedEvent(
            IngestionRequest request,
            IngestionRequestDocument document
    ) {
        IngestionRequestedEvent event = IngestionRequestedEvent.builder()
                .requestId(document.getRequestId())
                .externalMessageId(document.getExternalMessageId())
                .deduplicationKey(document.getDeduplicationKey())
                .communicationType(request.getCommunicationType())
                .sender(request.getSender())
                .recipients(request.getRecipients())
                .subject(request.getSubject())
                .body(request.getBody())
                .messageTimestamp(request.getMessageTimestamp())
                .threadId(request.getThreadId())
                .attachments(document.getStagedAttachments())
                .retentionMinutes(request.getRetentionMinutes())
                .build();

        kafkaTemplate
                .send(INGESTION_REQUESTED_TOPIC, document.getRequestId(), event)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        log.error(
                                "Failed to publish {} requestId={}",
                                INGESTION_REQUESTED_TOPIC,
                                document.getRequestId(),
                                throwable
                        );
                        return;
                    }

                    log.debug(
                            "Published {} requestId={} partition={} offset={}",
                            INGESTION_REQUESTED_TOPIC,
                            document.getRequestId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset()
                    );
                });
    }

    private void deleteQuietly(String key) {
        try {
            storageService.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Could not delete staged object {}: {}", key, exception.getMessage());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
