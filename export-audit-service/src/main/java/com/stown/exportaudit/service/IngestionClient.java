package com.stown.exportaudit.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stown.exportaudit.config.IngestionProperties;
import com.stown.exportaudit.domain.AttachmentMetadata;
import com.stown.exportaudit.domain.MessageDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * HTTP client for the ingestion service read API. Fetches message content and
 * attachment metadata by id so the export service can build an evidence
 * package without reading the ingestion service's {@code messages} MongoDB
 * collection directly. This is what makes the export service respect NFR-1
 * (no shared database schemas between services): it talks to ingestion's API,
 * not its database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionClient {

    private final RestClient ingestionRestClient;
    private final IngestionProperties ingestionProperties;

    /**
     * Fetches the full content of every message whose id is in the supplied
     * set in a single round-trip. Missing ids are omitted by the ingestion
     * API, so the returned list may be smaller than the input set.
     */
    public List<MessageDocument> getMessages(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        log.debug(
                "Fetching {} message(s) from ingestion at {}",
                ids.size(),
                ingestionProperties.getBaseUrl()
        );

        MessageDto[] messages = ingestionRestClient.post()
                .uri("/api/ingestion/messages/batch")
                .body(new BatchGetRequest(ids))
                .retrieve()
                .body(MessageDto[].class);

        if (messages == null || messages.length == 0) {
            log.info("Ingestion returned no messages for {} requested id(s)", ids.size());
            return List.of();
        }

        List<MessageDocument> result = new ArrayList<>(messages.length);
        for (MessageDto dto : messages) {
            result.add(dto.toMessageDocument());
        }

        log.info("Ingestion returned {} of {} requested messages", result.size(), ids.size());
        return result;
    }

    // Local DTO matching the ingestion service's MessageResponse JSON. Only the
    // fields the export package needs are captured; unknown fields are ignored.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageDto(
            String id,
            String deduplicationKey,
            String externalMessageId,
            String communicationType,
            String sender,
            List<String> recipients,
            String subject,
            String body,
            Instant messageTimestamp,
            String threadId,
            List<AttachmentMetadata> attachments,
            Instant createdAt
    ) {
        MessageDocument toMessageDocument() {
            return MessageDocument.builder()
                    .id(id)
                    .deduplicationKey(deduplicationKey)
                    .externalMessageId(externalMessageId)
                    .communicationType(communicationType)
                    .sender(sender)
                    .recipients(recipients)
                    .subject(subject)
                    .body(body)
                    .messageTimestamp(messageTimestamp)
                    .threadId(threadId)
                    .attachments(attachments)
                    .createdAt(createdAt)
                    .build();
        }
    }

    record BatchGetRequest(Set<String> ids) {
    }
}
