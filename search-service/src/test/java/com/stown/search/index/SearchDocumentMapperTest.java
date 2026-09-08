package com.stown.search.index;

import com.stown.search.domain.AttachmentMetadata;
import com.stown.search.domain.MessageDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchDocumentMapperTest {

    private final SearchDocumentMapper mapper = new SearchDocumentMapper();

    @Test
    void mapsEveryIndexedField() {
        Instant messageTimestamp = Instant.parse("2026-09-08T03:00:00Z");
        Instant indexedAt = Instant.parse("2026-09-08T03:00:05Z");

        MessageDocument message = MessageDocument.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .deduplicationKey("a".repeat(64))
                .externalMessageId("<external@example.com>")
                .communicationType("EMAIL")
                .sender("alice@example.com")
                .recipients(List.of("bob@example.com", "carol@example.com"))
                .subject("Quarterly review")
                .body("Please find the quarterly review attached.")
                .messageTimestamp(messageTimestamp)
                .threadId("thread-1")
                .attachments(List.of(
                        AttachmentMetadata.builder().attachmentId("a1").filename("review.pdf").build(),
                        AttachmentMetadata.builder().attachmentId("a2").filename("notes.txt").build()
                ))
                .createdAt(indexedAt)
                .build();

        SearchDocument document = mapper.toSearchDocument(message, indexedAt);

        assertThat(document.getMessageId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(document.getDeduplicationKey()).isEqualTo("a".repeat(64));
        assertThat(document.getExternalMessageId()).isEqualTo("<external@example.com>");
        assertThat(document.getCommunicationType()).isEqualTo("EMAIL");
        assertThat(document.getSender()).isEqualTo("alice@example.com");
        assertThat(document.getRecipients()).containsExactly("bob@example.com", "carol@example.com");
        assertThat(document.getSubject()).isEqualTo("Quarterly review");
        assertThat(document.getBody()).isEqualTo("Please find the quarterly review attached.");
        assertThat(document.getThreadId()).isEqualTo("thread-1");
        assertThat(document.getMessageTimestamp()).isEqualTo("2026-09-08T03:00:00Z");
        assertThat(document.getIndexedAt()).isEqualTo("2026-09-08T03:00:05Z");
        assertThat(document.getAttachmentCount()).isEqualTo(2);
        assertThat(document.getAttachmentFilenames()).containsExactly("review.pdf", "notes.txt");
    }

    @Test
    void defaultsCollectionsWhenAbsent() {
        MessageDocument message = MessageDocument.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .communicationType("CHAT")
                .build();

        SearchDocument document = mapper.toSearchDocument(message, Instant.parse("2026-09-08T03:00:05Z"));

        assertThat(document.getRecipients()).isEmpty();
        assertThat(document.getAttachmentFilenames()).isEmpty();
        assertThat(document.getAttachmentCount()).isZero();
        assertThat(document.getMessageTimestamp()).isNull();
    }

    @Test
    void usesTheMessageIdAsDocumentIdentity() {
        MessageDocument message = MessageDocument.builder().id("msg-1").build();

        SearchDocument first = mapper.toSearchDocument(message, Instant.now());
        SearchDocument second = mapper.toSearchDocument(message, Instant.now());

        assertThat(first.getMessageId()).isEqualTo(second.getMessageId()).isEqualTo("msg-1");
    }
}
