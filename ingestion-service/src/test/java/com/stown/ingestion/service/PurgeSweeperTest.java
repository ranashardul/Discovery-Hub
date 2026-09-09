package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.DispositionOutcome;
import com.stown.ingestion.messaging.MessageDisposedEvent;
import com.stown.ingestion.repository.DispositionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the sweeper that finishes interrupted disposition work.
 *
 * <p>This is the safety net for the one failure that matters: the message is
 * already gone from MongoDB, so if the object purge or the event publication
 * never completes, the binaries are orphaned and the search index keeps
 * serving content that no longer exists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurgeSweeperTest {

    @Mock
    private DispositionAuditRepository auditRepository;

    @Mock
    private S3StorageService storageService;

    @Mock
    private KafkaTemplate<String, MessageDisposedEvent> kafkaTemplate;

    private RetentionProperties properties;

    private PurgeSweeper sweeper;

    @BeforeEach
    void setUp() {
        properties = new RetentionProperties();
        properties.setEnabled(true);

        sweeper = new PurgeSweeper(auditRepository, storageService, kafkaTemplate, properties);

        when(auditRepository.save(any(DispositionAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(kafkaTemplate.send(anyString(), anyString(), any(MessageDisposedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void doesNothingWhenDispositionIsDisabled() {
        properties.setEnabled(false);

        sweeper.sweep();

        verifyNoInteractions(auditRepository);
    }

    @Test
    void doesNothingDuringADryRun() {
        properties.setDryRun(true);

        sweeper.sweep();

        // A dry run deletes nothing, so there is nothing to finish.
        verifyNoInteractions(auditRepository);
    }

    @Test
    void completesAnOutstandingObjectPurge() {
        DispositionAudit audit = pending("msg-1", List.of("key-1", "key-2"), true);

        when(auditRepository.findByOutcomeAndObjectsPurgedFalse(
                eq(DispositionOutcome.DELETED), any(Pageable.class)))
                .thenReturn(List.of(audit));

        sweeper.sweep();

        verify(storageService).delete("key-1");
        verify(storageService).delete("key-2");

        ArgumentCaptor<DispositionAudit> captor = ArgumentCaptor.forClass(DispositionAudit.class);
        verify(auditRepository).save(captor.capture());

        assertThat(captor.getValue().isObjectsPurged()).isTrue();
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void republishesTheEventWhenItWasNeverSent() {
        DispositionAudit audit = pending("msg-2", List.of("key-1"), false);

        when(auditRepository.findByOutcomeAndObjectsPurgedFalse(
                eq(DispositionOutcome.DELETED), any(Pageable.class)))
                .thenReturn(List.of(audit));

        sweeper.sweep();

        ArgumentCaptor<MessageDisposedEvent> event =
                ArgumentCaptor.forClass(MessageDisposedEvent.class);
        verify(kafkaTemplate).send(
                eq(DispositionService.MESSAGE_DISPOSED_TOPIC), eq("msg-2"), event.capture());

        assertThat(event.getValue().getMessageId()).isEqualTo("msg-2");
        assertThat(event.getValue().getAttachmentsPurged()).isEqualTo(1);
        assertThat(audit.isEventPublished()).isTrue();
    }

    @Test
    void doesNotRepublishAnEventAlreadySent() {
        DispositionAudit audit = pending("msg-3", List.of("key-1"), true);

        when(auditRepository.findByOutcomeAndObjectsPurgedFalse(
                eq(DispositionOutcome.DELETED), any(Pageable.class)))
                .thenReturn(List.of(audit));

        sweeper.sweep();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void recordsTheErrorAndKeepsGoingWhenAPurgeFails() {
        DispositionAudit failing = pending("msg-bad", List.of("key-bad"), true);
        DispositionAudit healthy = pending("msg-ok", List.of("key-ok"), true);

        when(auditRepository.findByOutcomeAndObjectsPurgedFalse(
                eq(DispositionOutcome.DELETED), any(Pageable.class)))
                .thenReturn(List.of(failing, healthy));
        org.mockito.Mockito.doThrow(new IllegalStateException("S3 still down"))
                .when(storageService).delete("key-bad");

        sweeper.sweep();

        // The failure is recorded for the next attempt, and the healthy record
        // is still completed rather than blocked behind it.
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).contains("S3 still down");
        verify(storageService).delete("key-ok");
    }

    @Test
    void handlesARecordWithNoObjectKeys() {
        DispositionAudit audit = pending("msg-4", null, false);

        when(auditRepository.findByOutcomeAndObjectsPurgedFalse(
                eq(DispositionOutcome.DELETED), any(Pageable.class)))
                .thenReturn(List.of(audit));

        sweeper.sweep();

        verify(storageService, never()).delete(anyString());
        assertThat(audit.isObjectsPurged()).isTrue();

        // A message with no attachments still needs its event published.
        ArgumentCaptor<MessageDisposedEvent> event =
                ArgumentCaptor.forClass(MessageDisposedEvent.class);
        verify(kafkaTemplate).send(anyString(), eq("msg-4"), event.capture());
        assertThat(event.getValue().getAttachmentsPurged()).isZero();
    }

    @Test
    void doesNothingWhenThereIsNoOutstandingWork() {
        when(auditRepository.findByOutcomeAndObjectsPurgedFalse(
                eq(DispositionOutcome.DELETED), any(Pageable.class)))
                .thenReturn(List.of());

        sweeper.sweep();

        verify(auditRepository, never()).save(any(DispositionAudit.class));
        verifyNoInteractions(storageService);
    }

    private DispositionAudit pending(String messageId, List<String> keys, boolean eventPublished) {
        return DispositionAudit.builder()
                .id("audit-" + messageId)
                .messageId(messageId)
                .externalMessageId("ext-" + messageId)
                .communicationType("EMAIL")
                .outcome(DispositionOutcome.DELETED)
                .reason("RETENTION")
                .s3Keys(keys)
                .objectsPurged(false)
                .eventPublished(eventPublished)
                .decidedAt(Instant.now())
                .build();
    }
}
