package com.stown.ingestion.service;

import com.stown.ingestion.api.StorageProofResponse;
import com.stown.ingestion.domain.AttachmentMetadata;
import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.repository.DispositionAuditRepository;
import com.stown.ingestion.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Reports whether a message and its attachment binaries still exist.
 *
 * <p>Reads only. Nothing here deletes, and it is deliberately tolerant of a
 * missing message: the interesting answer is usually about one that has just
 * been disposed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageProofService {

    static final String SOURCE_MESSAGE = "MESSAGE";
    static final String SOURCE_AUDIT = "DISPOSITION_AUDIT";
    static final String SOURCE_UNKNOWN = "UNKNOWN";

    private final MessageRepository messageRepository;
    private final DispositionAuditRepository auditRepository;
    private final S3StorageService storageService;

    public StorageProofResponse describe(String messageId) {
        Optional<MessageDocument> message = messageRepository.findById(messageId);

        if (message.isPresent()) {
            return fromMessage(message.get());
        }

        return auditRepository.findFirstByMessageIdOrderByDecidedAtDesc(messageId)
                .map(this::fromAudit)
                .orElseGet(() -> new StorageProofResponse(
                        messageId,
                        false,
                        SOURCE_UNKNOWN,
                        storageService.getBucket(),
                        null,
                        List.of()
                ));
    }

    private StorageProofResponse fromMessage(MessageDocument message) {
        List<AttachmentMetadata> attachments = message.getAttachments() == null
                ? List.of()
                : message.getAttachments();

        return new StorageProofResponse(
                message.getId(),
                true,
                SOURCE_MESSAGE,
                storageService.getBucket(),
                null,
                attachments.stream()
                        .map(AttachmentMetadata::getS3Key)
                        .map(this::probe)
                        .toList()
        );
    }

    private StorageProofResponse fromAudit(DispositionAudit audit) {
        List<String> keys = audit.getS3Keys() == null ? List.of() : audit.getS3Keys();

        return new StorageProofResponse(
                audit.getMessageId(),
                false,
                SOURCE_AUDIT,
                audit.getS3Bucket() != null ? audit.getS3Bucket() : storageService.getBucket(),
                audit.getCompletedAt(),
                keys.stream().map(this::probe).toList()
        );
    }

    /**
     * A storage failure is reported as "still present" rather than as absent.
     *
     * <p>This answer is used as evidence that a purge completed, so the safe
     * direction to fail in is the one that says the object might still be
     * there. Claiming a clean purge because S3 was unreachable would be the
     * one wrong answer.
     */
    private StorageProofResponse.StoredObject probe(String key) {
        if (key == null) {
            return new StorageProofResponse.StoredObject(null, false);
        }

        try {
            return new StorageProofResponse.StoredObject(key, storageService.exists(key));
        } catch (RuntimeException exception) {
            log.warn("Could not probe object key={} reason={}", key, exception.getMessage());
            return new StorageProofResponse.StoredObject(key, true);
        }
    }
}
