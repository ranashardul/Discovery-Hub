package com.stown.search.messaging;

import com.stown.search.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageIngestedListener {

    private final IndexingService indexingService;

    @KafkaListener(
            topics = "${app.search.topic}",
            groupId = "${spring.kafka.consumer.group-id:search-service}"
    )
    public void onMessageIngested(MessageIngestedEvent event) {
        if (event == null || event.getMessageId() == null || event.getMessageId().isBlank()) {
            log.warn("Discarding message.ingested event without messageId event={}", event);
            return;
        }

        String eventId = event.getEventId() == null ? null : event.getEventId().toString();
        log.debug(
                "Received message.ingested messageId={} eventId={} occurredAt={}",
                event.getMessageId(),
                eventId,
                event.getOccurredAt()
        );

        indexingService.indexMessage(event.getMessageId(), eventId);
    }
}
