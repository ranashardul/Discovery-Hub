package com.stown.casehold.messaging;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.stown.casehold.domain.OutboxEventEntity;
import com.stown.casehold.domain.OutboxStatus;
import com.stown.casehold.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes a domain event to the transactional outbox. Call from inside a
 * {@code @Transactional} service method so the outbox row commits atomically
 * with the business change. The {@link OutboxPublisher} drains it later.
 */
@Component
@RequiredArgsConstructor
public class EventOutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void write(String eventType, String aggregateId, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            outboxEventRepository.save(OutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .eventType(eventType)
                    .aggregateId(aggregateId)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .createdAt(Instant.now())
                    .attempts(0)
                    .build());
        } catch (JacksonException exception) {
            throw new EventSerializationException(eventType, aggregateId, exception);
        }
    }
}
