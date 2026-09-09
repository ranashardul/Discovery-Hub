package com.stown.ingestion.worker;

import com.stown.ingestion.domain.DispositionStatus;
import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.domain.OutboxStatus;
import com.stown.ingestion.domain.StagedAttachment;
import com.stown.ingestion.messaging.IngestionRequestedEvent;
import com.stown.ingestion.repository.IngestionRequestRepository;
import com.stown.ingestion.repository.MessageRepository;
import com.stown.ingestion.service.AttachmentStorageService;
import com.stown.ingestion.service.OutboxPublisher;
import com.stown.ingestion.service.RetentionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage half of ingestion. Consumes {@code ingestion.requested}, moves
 * attachment binaries into their durable location, persists the message and
 * hands the {@code message.ingested} event to the outbox publisher.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionWorker {

    private final IngestionRequestRepository requestRepository;
    private final MessageRepository messageRepository;
    private final AttachmentStorageService attachmentStorageService;
    private final OutboxPublisher outboxPublisher;
    private final RetentionPolicy retentionPolicy;

    @KafkaListener(
            topics = "ingestion.requested",
            groupId = "${spring.kafka.consumer.group-id:ingestion-service}"
    )
    public void onIngestionRequested(IngestionRequestedEvent event) {
        IngestionRequestDocument request = requestRepository
                .findById(event.getRequestId())
                .orElse(null);

        if (request == null) {
            log.warn(
                    "Ignoring event for unknown requestId={} deduplicationKey={}",
                    event.getRequestId(),
                    event.getDeduplicationKey()
            );
            return;
        }

        if (request.getStatus() == IngestionStatus.INGESTED) {
            log.debug(
                    "Request already ingested requestId={} messageId={}",
                    request.getRequestId(),
                    request.getMessageId()
            );
            return;
        }

        Optional<MessageDocument> alreadyStored =
                messageRepository.findByDeduplicationKey(event.getDeduplicationKey());

        if (alreadyStored.isPresent()) {
            markIngested(request, alreadyStored.get().getId());
            return;
        }

        // The message ID is assigned once and reused by every retry, so it stays
        // immutable and keeps S3 keys stable.
        String messageId = request.getMessageId() == null
                ? UUID.randomUUID().toString()
                : request.getMessageId();

        markProcessing(request, messageId);

        try {
            MessageDocument message = store(event, request, messageId);

            markIngested(request, message.getId());
            outboxPublisher.publish(message);

            log.info(
                    "Ingested message messageId={} requestId={} attachments={}",
                    message.getId(),
                    request.getRequestId(),
                    message.getAttachments() == null ? 0 : message.getAttachments().size()
            );
        } catch (DuplicateKeyException exception) {
            MessageDocument winner = messageRepository
                    .findByDeduplicationKey(event.getDeduplicationKey())
                    .or(() -> externalLookup(event.getExternalMessageId()))
                    .orElseThrow(() -> exception);

            markIngested(request, winner.getId());

            log.info(
                    "Concurrent duplicate resolved messageId={} requestId={}",
                    winner.getId(),
                    request.getRequestId()
            );
        } catch (RuntimeException exception) {
            markFailed(request, exception);

            // Rethrown so the container retry policy and dead-letter topic apply.
            throw exception;
        }
    }

    private MessageDocument store(
            IngestionRequestedEvent event,
            IngestionRequestDocument request,
            String messageId
    ) {
        Instant now = Instant.now();

        MessageDocument message = MessageDocument.builder()
                .id(messageId)
                .deduplicationKey(event.getDeduplicationKey())
                .externalMessageId(event.getExternalMessageId())
                .requestId(request.getRequestId())
                .communicationType(event.getCommunicationType())
                .sender(event.getSender())
                .recipients(event.getRecipients())
                .subject(event.getSubject())
                .body(event.getBody())
                .messageTimestamp(event.getMessageTimestamp())
                .threadId(event.getThreadId())
                .attachments(attachmentStorageService.materialize(
                        messageId,
                        event.getAttachments()
                ))
                .createdAt(now)
                // Retention is resolved per communication type and stored, so
                // the policy applied to this message stays auditable even if
                // configuration changes later.
                .retentionUntil(retentionPolicy.expiryFor(event.getCommunicationType(), now))
                .holdIds(List.of())
                .holdCount(0)
                .dispositionStatus(DispositionStatus.ACTIVE)
                .outboxStatus(OutboxStatus.PENDING)
                .outboxAttempts(0)
                .build();

        return messageRepository.insert(message);
    }

    private Optional<MessageDocument> externalLookup(String externalMessageId) {
        return externalMessageId == null
                ? Optional.empty()
                : messageRepository.findByExternalMessageId(externalMessageId);
    }

    private void markProcessing(IngestionRequestDocument request, String messageId) {
        request.setStatus(IngestionStatus.PROCESSING);
        request.setMessageId(messageId);
        request.setAttempts(request.getAttempts() + 1);
        request.setUpdatedAt(Instant.now());

        requestRepository.save(request);
    }

    private void markIngested(IngestionRequestDocument request, String messageId) {
        request.setStatus(IngestionStatus.INGESTED);
        request.setMessageId(messageId);
        request.setLastError(null);
        request.setUpdatedAt(Instant.now());

        requestRepository.save(request);

        cleanUpStaging(request);
    }

    private void markFailed(IngestionRequestDocument request, RuntimeException exception) {
        request.setStatus(IngestionStatus.FAILED);
        request.setLastError(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        request.setUpdatedAt(Instant.now());

        requestRepository.save(request);

        log.error(
                "Failed to ingest requestId={} attempt={}",
                request.getRequestId(),
                request.getAttempts(),
                exception
        );
    }

    private void cleanUpStaging(IngestionRequestDocument request) {
        List<StagedAttachment> staged = request.getStagedAttachments();

        if (staged == null || staged.isEmpty()) {
            return;
        }

        attachmentStorageService.discardStaged(staged);

        request.setStagedAttachments(List.of());
        requestRepository.save(request);
    }
}
