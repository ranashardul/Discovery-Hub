package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.AuditEventDocument;
import com.stown.exportaudit.messaging.AuditEvent;
import com.stown.exportaudit.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @InjectMocks
    private AuditService auditService;

    @Test
    void recordsEventAsAppendOnlyDocument() {
        when(auditEventRepository.insert(any(AuditEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuditEvent event = AuditEvent.builder()
                .eventId("evt-1")
                .caseId("case-1")
                .action("EXPORT_REQUESTED")
                .targetType("EXPORT")
                .targetId("export-1")
                .actor("investigator@example.com")
                .status("QUEUED")
                .timestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .details(Map.of("scope", "CASE"))
                .build();

        AuditEventDocument saved = auditService.record(event);

        ArgumentCaptor<AuditEventDocument> captor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(auditEventRepository).insert(captor.capture());

        AuditEventDocument document = captor.getValue();
        assertThat(document.getEventId()).isEqualTo("evt-1");
        assertThat(document.getAction()).isEqualTo("EXPORT_REQUESTED");
        assertThat(document.getCaseId()).isEqualTo("case-1");
        assertThat(document.getActor()).isEqualTo("investigator@example.com");
        assertThat(document.getStatus()).isEqualTo("QUEUED");
        assertThat(document.getDetails()).containsEntry("scope", "CASE");
        assertThat(document.getReceivedAt()).isNotNull();

        assertThat(saved).isEqualTo(document);
    }

    @Test
    void deduplicatesOnEventId() {
        AuditEventDocument existing = AuditEventDocument.builder()
                .id("doc-1")
                .eventId("evt-1")
                .action("EXPORT_REQUESTED")
                .build();

        when(auditEventRepository.insert(any(AuditEventDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(auditEventRepository.findByEventId("evt-1"))
                .thenReturn(Optional.of(existing));

        AuditEvent event = AuditEvent.builder()
                .eventId("evt-1")
                .action("EXPORT_REQUESTED")
                .build();

        AuditEventDocument result = auditService.record(event);

        assertThat(result).isEqualTo(existing);
        verify(auditEventRepository, never()).save(any(AuditEventDocument.class));
    }

    @Test
    void assignsEventIdWhenMissing() {
        when(auditEventRepository.insert(any(AuditEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuditEvent event = AuditEvent.builder()
                .action("CASE_CREATED")
                .build();

        auditService.record(event);

        ArgumentCaptor<AuditEventDocument> captor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(auditEventRepository).insert(captor.capture());

        assertThat(captor.getValue().getEventId()).isNotBlank();
    }

    @Test
    void findByCaseIdDelegatesToRepository() {
        AuditEventDocument doc = AuditEventDocument.builder()
                .id("doc-1")
                .caseId("case-1")
                .action("EXPORT_REQUESTED")
                .build();

        when(auditEventRepository.findByCaseIdOrderByTimestampDesc("case-1"))
                .thenReturn(List.of(doc));

        List<AuditEventDocument> result = auditService.findByCaseId("case-1");

        assertThat(result).containsExactly(doc);
    }

    @Test
    void findByTargetIdDelegatesToRepository() {
        AuditEventDocument doc = AuditEventDocument.builder()
                .id("doc-1")
                .targetId("export-1")
                .action("EXPORT_DOWNLOADED")
                .build();

        when(auditEventRepository.findByTargetIdOrderByTimestampDesc("export-1"))
                .thenReturn(List.of(doc));

        List<AuditEventDocument> result = auditService.findByTargetId("export-1");

        assertThat(result).containsExactly(doc);
    }

    @Test
    void recordOverloadBuildsEventWithDefaults() {
        when(auditEventRepository.insert(any(AuditEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        auditService.record(
                "HOLD_PLACED",
                "case-1",
                "HOLD",
                "hold-1",
                "compliance@example.com",
                "ACTIVE",
                Map.of("reason", "litigation")
        );

        ArgumentCaptor<AuditEventDocument> captor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(auditEventRepository).insert(captor.capture());

        assertThat(captor.getValue().getAction()).isEqualTo("HOLD_PLACED");
        assertThat(captor.getValue().getTargetType()).isEqualTo("HOLD");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void capturesStructuredBeforeAndAfterForTransitions() {
        when(auditEventRepository.insert(any(AuditEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        auditService.record(
                "CASE_STATUS_CHANGED",
                "case-1",
                "CASE",
                "case-1",
                "investigator@example.com",
                "ACTIVE",
                Map.of("status", "DRAFT"),
                Map.of("status", "ACTIVE"),
                Map.of("reason", "investigation started")
        );

        ArgumentCaptor<AuditEventDocument> captor = ArgumentCaptor.forClass(AuditEventDocument.class);
        verify(auditEventRepository).insert(captor.capture());

        assertThat(captor.getValue().getBefore()).containsEntry("status", "DRAFT");
        assertThat(captor.getValue().getAfter()).containsEntry("status", "ACTIVE");
        assertThat(captor.getValue().getDetails()).containsEntry("reason", "investigation started");
    }

    @Test
    void capturesBeforeAndAfterFromKafkaEvent() {
        when(auditEventRepository.insert(any(AuditEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuditEvent event = AuditEvent.builder()
                .eventId("evt-transition-1")
                .caseId("case-1")
                .action("CASE_STATUS_CHANGED")
                .targetType("CASE")
                .targetId("case-1")
                .actor("investigator@example.com")
                .status("ACTIVE")
                .before(Map.of("status", "DRAFT"))
                .after(Map.of("status", "ACTIVE"))
                .build();

        AuditEventDocument saved = auditService.record(event);

        assertThat(saved.getBefore()).containsEntry("status", "DRAFT");
        assertThat(saved.getAfter()).containsEntry("status", "ACTIVE");
    }
}
