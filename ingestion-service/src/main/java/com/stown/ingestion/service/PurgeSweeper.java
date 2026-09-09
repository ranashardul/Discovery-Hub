package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.DispositionOutcome;
import com.stown.ingestion.messaging.MessageDisposedEvent;
import com.stown.ingestion.repository.DispositionAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finishes disposition work that did not complete.
 *
 * <p>Two cases, both detectable from the audit record because it is written
 * before the message is deleted:
 *
 * <ul>
 *   <li>{@code PENDING} - the process died between the audit write and the
 *       delete, so the message may still exist and the objects are untouched.</li>
 *   <li>{@code DELETED} with objects or the event outstanding - the message is
 *       gone but the binaries were not purged or search was never told, which
 *       would leave deleted content searchable.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurgeSweeper {

    private static final int BATCH_SIZE = 100;

    private final DispositionAuditRepository auditRepository;
    private final S3StorageService storageService;
    private final KafkaTemplate<String, MessageDisposedEvent> kafkaTemplate;
    private final RetentionProperties properties;

    @Scheduled(
            initialDelayString = "${app.retention.purge-sweep-interval-ms:60000}",
            fixedDelayString = "${app.retention.purge-sweep-interval-ms:60000}"
    )
    public void sweep() {
        if (!properties.isEnabled() || properties.isDryRun()) {
            return;
        }

        List<DispositionAudit> outstanding = auditRepository.findByOutcomeAndObjectsPurgedFalse(
                DispositionOutcome.DELETED,
                PageRequest.of(0, BATCH_SIZE, Sort.by("decidedAt"))
        );

        if (outstanding.isEmpty()) {
            return;
        }

        log.info("Purge sweeper found {} incomplete disposition records", outstanding.size());

        for (DispositionAudit audit : outstanding) {
            try {
                finish(audit);
            } catch (RuntimeException exception) {
                audit.setAttempts(audit.getAttempts() + 1);
                audit.setLastError(exception.getClass().getSimpleName() + ": " + exception.getMessage());
                auditRepository.save(audit);

                log.error(
                        "Purge retry failed messageId={} attempts={}",
                        audit.getMessageId(),
                        audit.getAttempts(),
                        exception
                );
            }
        }
    }

    private void finish(DispositionAudit audit) {
        if (audit.getS3Keys() != null) {
            audit.getS3Keys().forEach(storageService::delete);
        }

        audit.setObjectsPurged(true);

        if (!audit.isEventPublished()) {
            MessageDisposedEvent event = MessageDisposedEvent.builder()
                    .eventId(UUID.randomUUID())
                    .messageId(audit.getMessageId())
                    .externalMessageId(audit.getExternalMessageId())
                    .communicationType(audit.getCommunicationType())
                    .reason(audit.getReason())
                    .attachmentsPurged(audit.getS3Keys() == null ? 0 : audit.getS3Keys().size())
                    .retentionUntil(audit.getRetentionUntil())
                    .disposedAt(Instant.now())
                    .build();

            kafkaTemplate.send(DispositionService.MESSAGE_DISPOSED_TOPIC, audit.getMessageId(), event)
                    .join();
            audit.setEventPublished(true);
        }

        audit.setCompletedAt(Instant.now());
        auditRepository.save(audit);

        log.info("Completed outstanding disposition messageId={}", audit.getMessageId());
    }
}
