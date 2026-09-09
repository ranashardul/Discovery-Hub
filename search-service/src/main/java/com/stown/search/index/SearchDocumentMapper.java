package com.stown.search.index;

import com.stown.search.domain.AttachmentMetadata;
import com.stown.search.domain.MessageDocument;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
public class SearchDocumentMapper {

    public SearchDocument toSearchDocument(MessageDocument message, Instant indexedAt) {
        List<AttachmentMetadata> attachments = message.getAttachments() == null
                ? List.of()
                : message.getAttachments();

        List<String> filenames = attachments.stream()
                .map(AttachmentMetadata::getFilename)
                .filter(Objects::nonNull)
                .toList();

        return SearchDocument.builder()
                .messageId(message.getId())
                .deduplicationKey(message.getDeduplicationKey())
                .externalMessageId(message.getExternalMessageId())
                .communicationType(message.getCommunicationType())
                .sender(message.getSender())
                .recipients(message.getRecipients() == null ? List.of() : message.getRecipients())
                .subject(message.getSubject())
                .body(message.getBody())
                .threadId(message.getThreadId())
                .messageTimestamp(format(message.getMessageTimestamp()))
                .attachmentCount(attachments.size())
                .attachmentFilenames(filenames)
                .indexedAt(format(indexedAt))
                .holdCount(message.getHoldCount())
                .holdIds(message.getHoldIds() == null ? List.of() : message.getHoldIds())
                .dispositionStatus(message.getDispositionStatus())
                .build();
    }

    private String format(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
