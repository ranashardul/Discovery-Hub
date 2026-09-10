package com.stown.ingestion.service;

import com.stown.ingestion.config.RetentionProperties;
import com.stown.ingestion.domain.AttachmentMetadata;
import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.DispositionOutcome;
import com.stown.ingestion.domain.DispositionRun;
import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.IngestionStatus;
import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.messaging.MessageDisposedEvent;
import com.stown.ingestion.repository.DispositionAuditRepository;
import com.stown.ingestion.repository.DispositionRunRepository;
import com.stown.ingestion.repository.IngestionRequestRepository;
import com.stown.ingestion.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the disposition rules, with every collaborator mocked.
 *
 * <p>The integration tests prove the behaviour against real MongoDB, Kafka and
 * S3. These tests pin the decisions the service makes, which is where the
 * compliance risk lives: what it deletes, what it refuses to delete, and the
 * order in which it writes the audit trail relative to the delete.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispositionServiceTest {

    private static final String BUCKET = "test-bucket";

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private IngestionRequestRepository requestRepository;

    @Mock
    private DispositionAuditRepository auditRepository;

    @Mock
    private DispositionRunRepository runRepository;

    @Mock
    private S3StorageService storageService;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private KafkaTemplate<String, MessageDisposedEvent> kafkaTemplate;

    @Mock
    private AuditPublisher auditPublisher;

    private RetentionProperties properties;

    private DispositionService service;

    @BeforeEach
    void setUp() {
        properties = new RetentionProperties();
        properties.setEnabled(true);
        properties.setBatchSize(200);
        properties.setMaxDeletesPerRun(500);

        service = new DispositionService(
                messageRepository,
                requestRepository,
                auditRepository,
                runRepository,
                storageService,
                mongoTemplate,
                kafkaTemplate,
                properties,
                auditPublisher
        );

        when(storageService.getBucket()).thenReturn(BUCKET);
        when(auditRepository.save(any(DispositionAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(DispositionRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(kafkaTemplate.send(anyString(), anyString(), any(MessageDisposedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ------------------------------------------------------------ scheduling

    @Test
    void scheduledRunDoesNothingWhenDispositionIsDisabled() {
        properties.setEnabled(false);

        service.runScheduledDisposition();

        // The master switch must prevent even the scan, so a disabled
        // environment cannot delete anything by accident.
        verifyNoInteractions(messageRepository);
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void scheduledRunScansWhenEnabled() {
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of());

        service.runScheduledDisposition();

        verify(messageRepository).findByRetentionUntilLessThanEqual(any(Instant.class), any(Pageable.class));
    }

    // ------------------------------------------------------------- hold rules

    @Test
    void skipsAMessageUnderLegalHoldAndNeverAttemptsTheDelete() {
        MessageDocument held = message("held-1", 2, List.of("hold-a", "hold-b"));
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(held));

        DispositionRun run = service.disposeExpired();

        assertThat(run.getSkippedOnHold()).isEqualTo(1);
        assertThat(run.getDeleted()).isZero();

        // FR-4.2: no delete may even be attempted for held evidence.
        verify(mongoTemplate, never()).findAndRemove(any(Query.class), eq(MessageDocument.class));
        verify(storageService, never()).delete(anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void recordsTheHoldStateOnTheSkippedAuditEntry() {
        MessageDocument held = message("held-2", 1, List.of("hold-x"));
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(held));

        service.disposeExpired();

        DispositionAudit audit = captureAudits().getLast();
        assertThat(audit.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_ON_HOLD);
        assertThat(audit.getHoldCountAtDecision()).isEqualTo(1);
        assertThat(audit.getHoldIdsAtDecision()).containsExactly("hold-x");
        assertThat(audit.getCompletedAt()).isNotNull();
    }

    @Test
    void treatsAHoldArrivingMidRunAsSkippedRatherThanDeleted() {
        MessageDocument candidate = message("race-1", 0, List.of());
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));

        // The conditional delete is filtered on holdCount = 0, so a hold that
        // lands after the scan makes it match nothing.
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(null);

        DispositionRun run = service.disposeExpired();

        assertThat(run.getSkippedOnHold()).isEqualTo(1);
        assertThat(run.getDeleted()).isZero();
        verify(storageService, never()).delete(anyString());

        DispositionAudit audit = captureAudits().getLast();
        assertThat(audit.getOutcome()).isEqualTo(DispositionOutcome.SKIPPED_ON_HOLD);
    }

    // ------------------------------------------------------------- happy path

    @Test
    void deletesAnExpiredUnheldMessageAndPurgesItsObjects() {
        MessageDocument candidate = message("gone-1", 0, List.of());
        candidate.setAttachments(List.of(
                attachment("messages/gone-1/attachments/att-001/a.pdf"),
                attachment("messages/gone-1/attachments/att-002/b.pdf")
        ));

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(candidate);

        DispositionRun run = service.disposeExpired();

        assertThat(run.getDeleted()).isEqualTo(1);
        assertThat(run.getSkippedOnHold()).isZero();
        assertThat(run.getS3ObjectsPurged()).isEqualTo(2);

        verify(storageService).delete("messages/gone-1/attachments/att-001/a.pdf");
        verify(storageService).delete("messages/gone-1/attachments/att-002/b.pdf");
        verify(kafkaTemplate).send(eq(DispositionService.MESSAGE_DISPOSED_TOPIC), eq("gone-1"), any());
    }

    @Test
    void writesTheAuditRecordBeforeDeletingTheDocument() {
        MessageDocument candidate = message("order-1", 0, List.of());
        candidate.setAttachments(List.of(attachment("messages/order-1/attachments/att-001/a.pdf")));

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(candidate);

        service.disposeExpired();

        // Audit first, then delete, then purge. Any other order can strand the
        // binaries with nothing recording their keys.
        var order = inOrder(auditRepository, mongoTemplate, storageService);
        order.verify(auditRepository).save(any(DispositionAudit.class));
        order.verify(mongoTemplate).findAndRemove(any(Query.class), eq(MessageDocument.class));
        order.verify(storageService).delete(anyString());
    }

    @Test
    void theIntentRecordCarriesTheObjectKeysSoAnInterruptedPurgeIsRecoverable() {
        MessageDocument candidate = message("keys-1", 0, List.of());
        candidate.setAttachments(List.of(attachment("messages/keys-1/attachments/att-001/a.pdf")));

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(candidate);

        // The service saves the same audit instance twice, mutating it in
        // place, so an ArgumentCaptor would only ever show the final state.
        // Recording the outcome at call time is what makes the transition
        // observable.
        List<DispositionOutcome> outcomesWhenSaved = new java.util.ArrayList<>();
        List<List<String>> keysWhenSaved = new java.util.ArrayList<>();

        when(auditRepository.save(any(DispositionAudit.class))).thenAnswer(invocation -> {
            DispositionAudit audit = invocation.getArgument(0);
            outcomesWhenSaved.add(audit.getOutcome());
            keysWhenSaved.add(audit.getS3Keys());
            return audit;
        });

        service.disposeExpired();

        // Written as PENDING before the delete, closed as DELETED after it.
        assertThat(outcomesWhenSaved)
                .containsExactly(DispositionOutcome.PENDING, DispositionOutcome.DELETED);

        // The keys are present on the very first write, which is what lets the
        // purge sweeper finish the job after a crash.
        assertThat(keysWhenSaved.getFirst())
                .containsExactly("messages/keys-1/attachments/att-001/a.pdf");
    }

    @Test
    void theIntentRecordNamesTheBucketBeingPurged() {
        MessageDocument candidate = message("bucket-1", 0, List.of());
        candidate.setAttachments(List.of(attachment("messages/bucket-1/attachments/att-001/a.pdf")));

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(candidate);

        service.disposeExpired();

        assertThat(captureAudits().getLast().getS3Bucket()).isEqualTo(BUCKET);
    }

    @Test
    void marksTheOriginatingRequestDisposed() {
        MessageDocument candidate = message("req-1", 0, List.of());
        candidate.setRequestId("request-1");

        IngestionRequestDocument request = IngestionRequestDocument.builder()
                .requestId("request-1")
                .status(IngestionStatus.INGESTED)
                .build();

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(candidate);
        when(requestRepository.findById("request-1")).thenReturn(Optional.of(request));

        service.disposeExpired();

        ArgumentCaptor<IngestionRequestDocument> captor =
                ArgumentCaptor.forClass(IngestionRequestDocument.class);
        verify(requestRepository).save(captor.capture());

        assertThat(captor.getValue().getStatus()).isEqualTo(IngestionStatus.DISPOSED);
    }

    @Test
    void aFailedKafkaPublicationStillLeavesTheMessageDeletedAndFlagsTheAudit() {
        MessageDocument candidate = message("kafka-down-1", 0, List.of());

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(candidate);
        when(kafkaTemplate.send(anyString(), anyString(), any(MessageDisposedEvent.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        DispositionRun run = service.disposeExpired();

        assertThat(run.getDeleted()).isEqualTo(1);

        DispositionAudit audit = captureAudits().getLast();
        assertThat(audit.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        // The sweeper uses this flag to retry, so the search projection is not
        // left holding content that no longer exists.
        assertThat(audit.isEventPublished()).isFalse();
    }

    // -------------------------------------------------------------- dry run

    @Test
    void dryRunReportsButDeletesNothing() {
        properties.setDryRun(true);

        MessageDocument candidate = message("dry-1", 0, List.of());
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(candidate));

        DispositionRun run = service.disposeExpired();

        assertThat(run.isDryRun()).isTrue();
        assertThat(run.getDeleted()).isEqualTo(1);

        verify(mongoTemplate, never()).findAndRemove(any(Query.class), eq(MessageDocument.class));
        verify(storageService, never()).delete(anyString());
        verify(auditRepository, never()).save(any(DispositionAudit.class));
    }

    // ------------------------------------------------------------ safety cap

    @Test
    void stopsAtTheConfiguredDeleteCap() {
        properties.setMaxDeletesPerRun(2);

        List<MessageDocument> candidates = List.of(
                message("cap-1", 0, List.of()),
                message("cap-2", 0, List.of()),
                message("cap-3", 0, List.of()),
                message("cap-4", 0, List.of())
        );

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(candidates);
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenAnswer(invocation -> candidates.get(0));

        DispositionRun run = service.disposeExpired();

        // A misconfigured retention period must not be able to empty the
        // collection in a single sweep.
        assertThat(run.getDeleted()).isEqualTo(2);
        assertThat(run.isDeleteCapReached()).isTrue();
        verify(mongoTemplate, times(2)).findAndRemove(any(Query.class), eq(MessageDocument.class));
    }

    // -------------------------------------------------------- failure handling

    @Test
    void aFailureOnOneMessageDoesNotAbortTheRun() {
        MessageDocument bad = message("bad-1", 0, List.of());
        bad.setAttachments(List.of(attachment("messages/bad-1/attachments/att-001/a.pdf")));
        MessageDocument good = message("good-1", 0, List.of());

        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(bad, good));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(bad, good);
        when(storageService.getBucket()).thenReturn(BUCKET);
        org.mockito.Mockito.doThrow(new IllegalStateException("S3 unavailable"))
                .when(storageService).delete("messages/bad-1/attachments/att-001/a.pdf");

        DispositionRun run = service.disposeExpired();

        assertThat(run.getFailed()).isEqualTo(1);
        assertThat(run.getDeleted()).isEqualTo(1);
        assertThat(run.getLastError()).contains("S3 unavailable");
    }

    @Test
    void doesNotPersistARunRecordWhenNothingWasScanned() {
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of());

        service.disposeExpired();

        verify(runRepository, never()).save(any(DispositionRun.class));
    }

    // ------------------------------------------------------- manual deletion

    @Test
    void manualDeleteRefusesAHeldMessage() {
        MessageDocument held = message("manual-held", 1, List.of("hold-m"));
        when(messageRepository.findById("manual-held")).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service.deleteOnRequest("manual-held"))
                .isInstanceOf(HeldMessageDeletionException.class)
                .hasMessageContaining("under legal hold");

        // FR-4.6: refused before anything is written or removed.
        verify(mongoTemplate, never()).findAndRemove(any(Query.class), eq(MessageDocument.class));
        verify(auditRepository, never()).save(any(DispositionAudit.class));
        verify(storageService, never()).delete(anyString());
    }

    @Test
    void manualDeleteRemovesAnUnheldMessageAndRecordsTheReason() {
        MessageDocument free = message("manual-free", 0, List.of());
        when(messageRepository.findById("manual-free")).thenReturn(Optional.of(free));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(free);

        DispositionAudit audit = service.deleteOnRequest("manual-free");

        assertThat(audit.getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        assertThat(audit.getReason()).isEqualTo("MANUAL");
        verify(kafkaTemplate).send(eq(DispositionService.MESSAGE_DISPOSED_TOPIC), eq("manual-free"), any());
    }

    @Test
    void manualDeleteRefusesWhenTheConditionalRemoveLosesToAConcurrentHold() {
        MessageDocument free = message("manual-race", 0, List.of());
        when(messageRepository.findById("manual-race")).thenReturn(Optional.of(free));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.deleteOnRequest("manual-race"))
                .isInstanceOf(HeldMessageDeletionException.class);

        verify(storageService, never()).delete(anyString());
    }

    @Test
    void manualDeleteOnAnUnknownMessageIsNotFound() {
        when(messageRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOnRequest("nope"))
                .isInstanceOf(MessageNotFoundException.class);
    }

    // ----------------------------------------------------------- audit trail

    /**
     * FR-4.6 is a claim about what the platform refuses to do, so the refusal
     * has to reach the audit trail. Without this the only evidence a deletion
     * was blocked is a log line.
     */
    @Test
    void publishesAnAuditEventWhenDeletionIsBlockedByAHold() {
        MessageDocument held = message("audit-held", 2, List.of("hold-a", "hold-b"));
        when(messageRepository.findById("audit-held")).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service.deleteOnRequest("audit-held"))
                .isInstanceOf(HeldMessageDeletionException.class);

        verify(auditPublisher).deletionBlocked("audit-held", 2, List.of("hold-a", "hold-b"));
    }

    @Test
    void publishesAnAuditEventWhenAMessageIsDeletedOnRequest() {
        MessageDocument free = message("audit-free", 0, List.of());
        when(messageRepository.findById("audit-free")).thenReturn(Optional.of(free));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(free);

        service.deleteOnRequest("audit-free");

        verify(auditPublisher).messageDeleted(eq("audit-free"), eq("MANUAL"), anyInt());
    }

    @Test
    void publishesAnAuditEventWhenADispositionRunCompletes() {
        MessageDocument expired = message("run-audit", 0, List.of());
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of(expired));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(expired);

        DispositionRun run = service.disposeExpired();

        verify(auditPublisher).dispositionRunCompleted(
                eq(run.getRunId()),
                eq(DispositionService.TRIGGER_SCHEDULED),
                eq(1L),
                anyLong(),
                anyLong(),
                anyLong(),
                eq(false)
        );
    }

    /** A run that scanned nothing is not worth an audit entry. */
    @Test
    void publishesNoAuditEventWhenARunFindsNothingToDo() {
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of());

        service.disposeExpired();

        verify(auditPublisher, never()).dispositionRunCompleted(
                anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean()
        );
    }

    // ------------------------------------------------- manual disposition run

    @Test
    void manualDispositionRecordsItsTriggerSoTheAuditTrailSaysAPersonRanIt() {
        when(messageRepository.findByRetentionUntilLessThanEqual(any(), any()))
                .thenReturn(List.of());

        assertThat(service.disposeOnRequest().getTrigger())
                .isEqualTo(DispositionService.TRIGGER_MANUAL);
    }

    /**
     * Disposition destroys data and is opt-in per environment. An HTTP
     * endpoint must not be a way around that switch.
     */
    @Test
    void manualDispositionIsRefusedWhenDispositionIsDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.disposeOnRequest())
                .isInstanceOf(DispositionDisabledException.class);

        verify(messageRepository, never()).findByRetentionUntilLessThanEqual(any(), any());
    }

    // -------------------------------------------------------------- helpers

    private List<DispositionAudit> captureAudits() {
        ArgumentCaptor<DispositionAudit> captor = ArgumentCaptor.forClass(DispositionAudit.class);
        verify(auditRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private MessageDocument message(String id, int holdCount, List<String> holdIds) {
        return MessageDocument.builder()
                .id(id)
                .externalMessageId("ext-" + id)
                .communicationType("EMAIL")
                .createdAt(Instant.now().minus(Duration.ofDays(1)))
                .retentionUntil(Instant.now().minusSeconds(60))
                .holdCount(holdCount)
                .holdIds(holdIds)
                .attachments(List.of())
                .build();
    }

    private AttachmentMetadata attachment(String key) {
        return AttachmentMetadata.builder()
                .attachmentId("att")
                .filename("a.pdf")
                .s3Bucket(BUCKET)
                .s3Key(key)
                .build();
    }
}
