package com.stown.search.messaging;

import com.stown.search.service.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Removes disposed messages from the index.
 *
 * <p>Runs in its own consumer group so that disposition progress is tracked
 * independently of ingestion: replaying one must not replay the other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageDisposedListener {

    private final IndexingService indexingService;

    @KafkaListener(
            topics = "${app.search.disposed-topic}",
            groupId = "${app.search.disposed-consumer-group:search-service-disposed}",
            containerFactory = "disposedListenerFactory"
    )
    public void onMessageDisposed(MessageDisposedEvent event) {
        if (event == null || event.getMessageId() == null || event.getMessageId().isBlank()) {
            log.warn("Discarding message.disposed event without messageId event={}", event);
            return;
        }

        String eventId = event.getEventId() == null ? null : event.getEventId().toString();
        log.debug(
                "Received message.disposed messageId={} eventId={} reason={} disposedAt={}",
                event.getMessageId(),
                eventId,
                event.getReason(),
                event.getDisposedAt()
        );

        indexingService.removeMessage(event.getMessageId(), eventId, event.getReason());
    }
}
