package com.stown.ingestion.messaging;

import com.stown.ingestion.service.LegalHoldProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies legal holds published by the case-hold service.
 *
 * <p>Uses a dedicated container factory with a String deserializer: that topic
 * carries raw JSON text from the producer's outbox and every Case and Hold
 * event type shares it, so the payload is parsed here and routed on
 * {@code eventType}. The service's default consumer factory cannot be reused
 * because it binds every value to {@code IngestionRequestedEvent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseHoldEventListener {

    private final LegalHoldProjectionService projectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.case-hold.topic:case-hold.events}",
            groupId = "${app.case-hold.consumer-group:ingestion-service-holds}",
            containerFactory = "caseHoldListenerFactory"
    )
    public void onCaseHoldEvent(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }

        CaseHoldEvent event;

        try {
            event = objectMapper.readValue(payload, CaseHoldEvent.class);
        } catch (Exception exception) {
            // Malformed payloads must not stall the partition; the case-hold
            // service owns the format and a bad record is not recoverable here.
            log.error("Discarding unparseable case-hold event payload={}", truncate(payload), exception);
            return;
        }

        if (event.getEventType() == null) {
            log.warn("Discarding case-hold event without eventType");
            return;
        }

        if (!event.isHoldCreated() && !event.isHoldReleased()) {
            // Case lifecycle events share this topic and are not our concern.
            log.debug("Ignoring case-hold event type={}", event.getEventType());
            return;
        }

        log.info(
                "Received {} holdId={} caseId={} scope={} eventId={}",
                event.getEventType(),
                event.getHoldId(),
                event.getCaseId(),
                event.getScope(),
                event.getEventId()
        );

        projectionService.apply(event);
    }

    private String truncate(String payload) {
        return payload.length() > 500 ? payload.substring(0, 500) + "..." : payload;
    }
}
