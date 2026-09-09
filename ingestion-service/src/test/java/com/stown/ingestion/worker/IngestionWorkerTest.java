package com.stown.ingestion.worker;

import com.stown.ingestion.domain.DispositionStatus;
import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.domain.OutboxStatus;
import com.stown.ingestion.messaging.IngestionRequestedEvent;
import com.stown.ingestion.repository.IngestionRequestRepository;
import com.stown.ingestion.repository.MessageRepository;
import com.stown.ingestion.service.AttachmentStorageService;
import com.stown.ingestion.service.OutboxPublisher;
import com.stown.ingestion.service.RetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the storage half of ingestion.
 *
 * <p>The properties worth pinning here are identity and retention: a retried
 * or replayed event must reuse the same message id rather than creating an
 * orphan, and the retention period must come from the policy for the
 * message's communication type.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionWorkerTest {

    @Mock
    private IngestionRequestRepository requestRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AttachmentStorageService attachmentStorageService;

    @Mock
    private OutboxPublisher outboxPublisher;

    @Mock
    private RetentionPolicy retentionPolicy;

    private IngestionWorker worker;

    @BeforeEach
    void setUp() {
        worker = new IngestionWorker(
                requestRepository,
                messageRepository,
                attachmentStorageService,
                outboxPublisher,
                retentionPolicy
        );

        when(messageRepository.insert(any(MessageDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(attachmentStorageService.materialize(anyString(), any())).thenReturn(List.of());
        when(retentionPolicy.expiryFor(anyString(), any(Instant.class)))
                .thenAnswer(invocation -> ((Instant) invocation.getArgument(1)).plusSeconds(60));
    }

    @Test
    void ignoresAnEventForAnUnknownRequest() {
        when(requestRepository.findById("missing")).thenReturn(Optional.empty());

        worker.onIngestionRequested(event("missing", "dedup-1", null));

        // Nothing can be stored without the request record that owns identity.
        verifyNoInteractions(messageRepository);
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void skipsARequestAlreadyIngested() {
        IngestionRequestDocument request = request("req-1", IngestionStatus.INGESTED, "msg-1");
        when(requestRepository.findById("req-1")).thenReturn(Optional.of(request));

        worker.onIngestionRequested(event("req-1", "dedup-1", null));

        // Delivery is at-least-once, so a replay must not store twice.
        verify(messageRepository, never()).insert(any(MessageDocument.class));
    }

    @Test
    void linksToAnExistingMessageWhenTheDeduplicationKeyIsAlreadyStored() {
        IngestionRequestDocument request = request("req-2", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-2")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey("dedup-2"))
                .thenReturn(Optional.of(MessageDocument.builder().id("existing-msg").build()));

        worker.onIngestionRequested(event("req-2", "dedup-2", null));

        verify(messageRepository, never()).insert(any(MessageDocument.class));
        assertThat(request.getStatus()).isEqualTo(IngestionStatus.INGESTED);
        assertThat(request.getMessageId()).isEqualTo("existing-msg");
    }

    @Test
    void reusesTheMessageIdAlreadyAssignedToTheRequest() {
        IngestionRequestDocument request = request("req-3", IngestionStatus.FAILED, "preassigned-id");
        when(requestRepository.findById("req-3")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey(anyString())).thenReturn(Optional.empty());

        worker.onIngestionRequested(event("req-3", "dedup-3", null));

        ArgumentCaptor<MessageDocument> captor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository).insert(captor.capture());

        // The id is part of every S3 key, so a retry must not mint a new one.
        assertThat(captor.getValue().getId()).isEqualTo("preassigned-id");
    }

    @Test
    void generatesAMessageIdWhenTheRequestHasNoneYet() {
        IngestionRequestDocument request = request("req-4", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-4")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey(anyString())).thenReturn(Optional.empty());

        worker.onIngestionRequested(event("req-4", "dedup-4", null));

        ArgumentCaptor<MessageDocument> captor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository).insert(captor.capture());

        assertThat(captor.getValue().getId()).isNotBlank();
        assertThat(request.getMessageId()).isEqualTo(captor.getValue().getId());
    }

    @Test
    void appliesTheRetentionPolicyForTheCommunicationType() {
        IngestionRequestDocument request = request("req-5", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-5")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey(anyString())).thenReturn(Optional.empty());

        IngestionRequestedEvent event = event("req-5", "dedup-5", null);
        event.setCommunicationType("CHAT");

        worker.onIngestionRequested(event);

        // The period must be resolved per type, not hardcoded.
        verify(retentionPolicy).expiryFor(org.mockito.ArgumentMatchers.eq("CHAT"), any(Instant.class));

        ArgumentCaptor<MessageDocument> captor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository).insert(captor.capture());
        assertThat(captor.getValue().getRetentionUntil()).isNotNull();
    }

    @Test
    void storesAMessageThatStartsUnheldAndActive() {
        IngestionRequestDocument request = request("req-6", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-6")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey(anyString())).thenReturn(Optional.empty());

        worker.onIngestionRequested(event("req-6", "dedup-6", null));

        ArgumentCaptor<MessageDocument> captor = ArgumentCaptor.forClass(MessageDocument.class);
        verify(messageRepository).insert(captor.capture());

        MessageDocument stored = captor.getValue();
        assertThat(stored.getHoldCount()).isZero();
        assertThat(stored.getHoldIds()).isEmpty();
        assertThat(stored.getDispositionStatus()).isEqualTo(DispositionStatus.ACTIVE);
        assertThat(stored.getOutboxStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void handsTheStoredMessageToTheOutboxPublisher() {
        IngestionRequestDocument request = request("req-7", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-7")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey(anyString())).thenReturn(Optional.empty());

        worker.onIngestionRequested(event("req-7", "dedup-7", null));

        verify(outboxPublisher).publish(any(MessageDocument.class));
        assertThat(request.getStatus()).isEqualTo(IngestionStatus.INGESTED);
    }

    @Test
    void resolvesAConcurrentDuplicateToTheWinningDocument() {
        IngestionRequestDocument request = request("req-8", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-8")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey("dedup-8"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(MessageDocument.builder().id("winner-id").build()));
        when(messageRepository.insert(any(MessageDocument.class)))
                .thenThrow(new DuplicateKeyException("unique index"));

        worker.onIngestionRequested(event("req-8", "dedup-8", null));

        // The unique index rejected this writer; it must adopt the winner
        // rather than fail the request.
        assertThat(request.getStatus()).isEqualTo(IngestionStatus.INGESTED);
        assertThat(request.getMessageId()).isEqualTo("winner-id");
    }

    @Test
    void marksTheRequestFailedAndRethrowsSoRetryAndDeadLetteringApply() {
        IngestionRequestDocument request = request("req-9", IngestionStatus.RECEIVED, null);
        when(requestRepository.findById("req-9")).thenReturn(Optional.of(request));
        when(messageRepository.findByDeduplicationKey(anyString())).thenReturn(Optional.empty());
        when(attachmentStorageService.materialize(anyString(), any()))
                .thenThrow(new IllegalStateException("S3 unavailable"));

        assertThatThrownBy(() -> worker.onIngestionRequested(event("req-9", "dedup-9", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3 unavailable");

        assertThat(request.getStatus()).isEqualTo(IngestionStatus.FAILED);
        assertThat(request.getLastError()).contains("S3 unavailable");
        // The id survives the failure so the retry reuses the same identity.
        assertThat(request.getMessageId()).isNotBlank();
    }

    private IngestionRequestedEvent event(String requestId, String dedupKey, String externalId) {
        IngestionRequestedEvent event = new IngestionRequestedEvent();
        event.setRequestId(requestId);
        event.setDeduplicationKey(dedupKey);
        event.setExternalMessageId(externalId);
        event.setCommunicationType("EMAIL");
        event.setSender("alice@example-bank.test");
        event.setRecipients(List.of("bob@example-bank.test"));
        event.setSubject("Subject");
        event.setBody("Body");
        event.setMessageTimestamp(Instant.parse("2026-09-09T03:00:00Z"));
        event.setAttachments(List.of());
        return event;
    }

    private IngestionRequestDocument request(String requestId, IngestionStatus status, String messageId) {
        return IngestionRequestDocument.builder()
                .requestId(requestId)
                .deduplicationKey("dedup-" + requestId)
                .status(status)
                .messageId(messageId)
                .attempts(0)
                .build();
    }
}
