package com.stown.ingestion.service;

import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.domain.OutboxStatus;
import com.stown.ingestion.messaging.MessageIngestedEvent;
import com.stown.ingestion.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes {@code message.ingested} from outbox state carried on the message
 * document itself.
 *
 * <p>Writing the outbox marker together with the message keeps the two atomic
 * without a multi-document transaction, so the same code works on a standalone
 * MongoDB container and on a replica set. Delivery is at-least-once:
 * consumers key on {@code messageId} and must be idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    public static final String MESSAGE_INGESTED_TOPIC = "message.ingested";

    private static final int BATCH_SIZE = 100;

    private final MessageRepository messageRepository;
    private final KafkaTemplate<String, MessageIngestedEvent> kafkaTemplate;

    /** Best-effort publication immediately after storage. */
    public void publish(MessageDocument message) {
        try {
            send(message);
        } catch (RuntimeException exception) {
            recordFailure(message, exception);
        }
    }

    /**
     * Recovers events that were never confirmed, for example when the broker
     * was unavailable at ingestion time.
     */
    @Scheduled(
            initialDelayString = "${app.outbox.initial-delay-ms:15000}",
            fixedDelayString = "${app.outbox.interval-ms:15000}"
    )
    public void republishPending() {
        List<MessageDocument> pending = messageRepository.findByOutboxStatus(
                OutboxStatus.PENDING,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (pending.isEmpty()) {
            return;
        }

        log.info("Republishing {} pending outbox events", pending.size());

        for (MessageDocument message : pending) {
            try {
                send(message);
            } catch (RuntimeException exception) {
                recordFailure(message, exception);
            }
        }
    }

    private void send(MessageDocument message) {
        MessageIngestedEvent event = MessageIngestedEvent.builder()
                .eventId(UUID.randomUUID())
                .messageId(message.getId())
                .deduplicationKey(message.getDeduplicationKey())
                .occurredAt(Instant.now())
                .build();

        var result = kafkaTemplate
                .send(MESSAGE_INGESTED_TOPIC, message.getId(), event)
                .join();

        message.setOutboxStatus(OutboxStatus.PUBLISHED);
        message.setOutboxPublishedAt(Instant.now());
        message.setOutboxLastError(null);
        messageRepository.save(message);

        log.info(
                "Published {} messageId={} eventId={} partition={} offset={}",
                MESSAGE_INGESTED_TOPIC,
                message.getId(),
                event.getEventId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset()
        );
    }

    private void recordFailure(MessageDocument message, RuntimeException exception) {
        message.setOutboxStatus(OutboxStatus.PENDING);
        message.setOutboxAttempts(message.getOutboxAttempts() + 1);
        message.setOutboxLastError(
                exception.getClass().getSimpleName() + ": " + exception.getMessage()
        );

        messageRepository.save(message);

        log.error(
                "Failed to publish {} messageId={} attempts={}",
                MESSAGE_INGESTED_TOPIC,
                message.getId(),
                message.getOutboxAttempts(),
                exception
        );
    }
}
