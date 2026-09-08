package com.stown.ingestion.service;

import com.stown.ingestion.domain.AttachmentMetadata;
import com.stown.ingestion.domain.StagedAttachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves staged attachment binaries into their durable, message-scoped
 * location and produces the metadata stored in MongoDB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentStorageService {

    private final S3StorageService storageService;

    /**
     * Copies each staged object to
     * {@code messages/{messageId}/attachments/{attachmentId}/{filename}}.
     * The copy is server side, so binaries never pass through Kafka or this
     * service's heap.
     */
    public List<AttachmentMetadata> materialize(
            String messageId,
            List<StagedAttachment> staged
    ) {
        List<AttachmentMetadata> attachments = new ArrayList<>();

        if (staged == null || staged.isEmpty()) {
            return attachments;
        }

        for (int index = 0; index < staged.size(); index++) {
            StagedAttachment attachment = staged.get(index);
            String attachmentId = "att-%03d".formatted(index + 1);
            String key = storageService.attachmentKey(
                    messageId,
                    attachmentId,
                    attachment.getFilename()
            );

            if (!storageService.exists(key)) {
                storageService.copy(attachment.getStagingKey(), key);
            }

            attachments.add(AttachmentMetadata.builder()
                    .attachmentId(attachmentId)
                    .filename(attachment.getFilename())
                    .contentType(attachment.getContentType())
                    .sizeBytes(attachment.getSizeBytes())
                    .sha256(attachment.getSha256())
                    .s3Bucket(storageService.getBucket())
                    .s3Key(key)
                    .s3Url(storageService.objectUrl(key))
                    .build());

            log.debug(
                    "Stored attachment messageId={} attachmentId={} key={}",
                    messageId,
                    attachmentId,
                    key
            );
        }

        return attachments;
    }

    public void discardStaged(List<StagedAttachment> staged) {
        if (staged == null) {
            return;
        }

        for (StagedAttachment attachment : staged) {
            try {
                storageService.delete(attachment.getStagingKey());
            } catch (RuntimeException exception) {
                log.warn(
                        "Could not delete staged object {}: {}",
                        attachment.getStagingKey(),
                        exception.getMessage()
                );
            }
        }
    }
}
