package com.stown.exportaudit.worker;

import com.stown.exportaudit.messaging.ExportRequestedEvent;
import com.stown.exportaudit.service.ExportJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code export.requested} and drives the asynchronous export
 * pipeline. The error handler in {@code KafkaConfig} retries with exponential
 * backoff and routes exhausted events to the dead-letter topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportWorker {

    private final ExportJobService exportJobService;

    @KafkaListener(
            topics = "${app.export.topic}",
            groupId = "${spring.kafka.consumer.group-id:export-audit-service}"
    )
    public void onExportRequested(ExportRequestedEvent event) {
        if (event == null || event.getExportId() == null || event.getExportId().isBlank()) {
            log.warn("Discarding export.requested event without exportId event={}", event);
            return;
        }

        log.debug(
                "Received export.requested exportId={} caseId={} eventId={}",
                event.getExportId(),
                event.getCaseId(),
                event.getEventId()
        );

        exportJobService.process(event);
    }
}
