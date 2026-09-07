package com.stown.ingestion.service;

import com.stown.ingestion.api.IngestionRequest;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.messaging.MessageIngestedEvent;
import com.stown.ingestion.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestionService {

    private final MessageRepository messageRepository;
    private final HashService hashService;
    private final KafkaTemplate<String, MessageIngestedEvent> kafkaTemplate;

    public MessageDocument ingest(IngestionRequest request) {
        String deduplicationKey =
                hashService.calculateDeduplicationKey(request);

        var existingMessage =
                messageRepository.findByDeduplicationKey(deduplicationKey);

        if (existingMessage.isPresent()) {
            return existingMessage.get();
        }

        Instant now = Instant.now();

        MessageDocument document = MessageDocument.builder()
                .id(UUID.randomUUID().toString())
                .deduplicationKey(deduplicationKey)
                .communicationType(request.getCommunicationType())
                .sender(request.getSender())
                .recipients(request.getRecipients())
                .subject(request.getSubject())
                .body(request.getBody())
                .messageTimestamp(request.getMessageTimestamp())
                .threadId(request.getThreadId())
                .createdAt(now)
                .retentionUntil(now.plus(365, ChronoUnit.DAYS))
                .holdCount(0)
                .dispositionStatus("ACTIVE")
                .build();

        try {
            MessageDocument saved = messageRepository.save(document);

            MessageIngestedEvent event = MessageIngestedEvent.builder()
                    .eventId(UUID.randomUUID())
                    .messageId(saved.getId())
                    .deduplicationKey(saved.getDeduplicationKey())
                    .occurredAt(Instant.now())
                    .build();

            kafkaTemplate.send(
                    "message.ingested",
                    saved.getId(),
                    event
            );

            return saved;
        } catch (DuplicateKeyException exception) {
            return messageRepository
                    .findByDeduplicationKey(deduplicationKey)
                    .orElseThrow(() -> exception);
        }
    }
}