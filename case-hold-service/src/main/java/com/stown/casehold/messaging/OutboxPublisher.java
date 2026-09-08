package com.stown.casehold.messaging;

import com.stown.casehold.config.CaseHoldProperties;
import com.stown.casehold.domain.OutboxEventEntity;
import com.stown.casehold.domain.OutboxStatus;
import com.stown.casehold.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Drains the transactional outbox to Kafka. Delivery is at-least-once: an event
 * is only marked PUBLISHED after the broker acknowledges it, so a crash between
 * send and mark leaves the row PENDING and it is re-sent next cycle. Consumers
 * must therefore be idempotent (key on the {@code eventId} in the payload).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final CaseHoldProperties properties;

    @Scheduled(
            initialDelayString = "${app.case-hold.outbox.initial-delay-ms:15000}",
            fixedDelayString = "${app.case-hold.outbox.interval-ms:5000}"
    )
    public void publishPending() {
        int batchSize = properties.getOutbox().getBatchSize();
        List<OutboxEventEntity> pending = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                PageRequest.of(0, batchSize)
        );

        if (pending.isEmpty()) {
            return;
        }

        log.info("Publishing {} pending outbox events", pending.size());

        for (OutboxEventEntity event : pending) {
            try {
                send(event);
            } catch (RuntimeException exception) {
                recordFailure(event, exception);
            }
        }
    }

    private void send(OutboxEventEntity event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.getTopic(),
                null,
                event.getAggregateId(),
                event.getPayload(),
                List.of(new RecordHeader(
                        "event_type",
                        event.getEventType().getBytes(StandardCharsets.UTF_8)
                ))
        );

        var result = kafkaTemplate.send(record).join();

        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(java.time.Instant.now());
        event.setLastError(null);
        outboxEventRepository.save(event);

        log.info(
                "Published {} aggregateId={} partition={} offset={}",
                properties.getTopic(),
                event.getAggregateId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset()
        );
    }

    private void recordFailure(OutboxEventEntity event, RuntimeException exception) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        outboxEventRepository.save(event);

        log.error(
                "Failed to publish {} aggregateId={} attempts={}",
                properties.getTopic(),
                event.getAggregateId(),
                event.getAttempts(),
                exception
        );
    }
}
