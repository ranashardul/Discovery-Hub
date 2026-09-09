package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.domain.MessageDocument;
import com.stown.exportaudit.service.CaseHoldClient;
import com.stown.exportaudit.service.IngestionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseHoldEvidenceProviderTest {

    @Mock
    private CaseHoldClient caseHoldClient;

    @Mock
    private IngestionClient ingestionClient;

    private CaseHoldEvidenceProvider evidenceProvider;

    @BeforeEach
    void setUp() {
        evidenceProvider = new CaseHoldEvidenceProvider(caseHoldClient, ingestionClient);
    }

    @Test
    void fetchesMessagesByCaseCommunicationIds() {
        when(caseHoldClient.getCaseCommunicationIds("case-1"))
                .thenReturn(List.of("msg-1", "msg-2"));

        MessageDocument msg1 = MessageDocument.builder()
                .id("msg-1")
                .messageTimestamp(Instant.parse("2026-09-02T10:00:00Z"))
                .build();
        MessageDocument msg2 = MessageDocument.builder()
                .id("msg-2")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build();

        when(ingestionClient.getMessages(anySet()))
                .thenReturn(List.of(msg1, msg2));

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .scope(ExportScope.CASE)
                        .caseId("case-1")
                        .build()
        );

        assertThat(result).hasSize(2);
        // Ordered by timestamp ascending.
        assertThat(result.get(0).getId()).isEqualTo("msg-2");
        assertThat(result.get(1).getId()).isEqualTo("msg-1");
    }

    @Test
    void fetchesMessagesByHoldCommunicationIds() {
        when(caseHoldClient.getHoldCommunicationIds("hold-1"))
                .thenReturn(List.of("msg-1"));

        MessageDocument msg1 = MessageDocument.builder()
                .id("msg-1")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build();

        when(ingestionClient.getMessages(anySet()))
                .thenReturn(List.of(msg1));

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .scope(ExportScope.LEGAL_HOLD)
                        .holdId("hold-1")
                        .build()
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("msg-1");
    }

    @Test
    void returnsEmptyWhenNoCommunicationsFound() {
        when(caseHoldClient.getCaseCommunicationIds("case-empty"))
                .thenReturn(List.of());

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .scope(ExportScope.CASE)
                        .caseId("case-empty")
                        .build()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoCaseIdOrHoldId() {
        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder().build()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void skipsMessagesNotFoundInIngestion() {
        // The ingestion read API omits ids that no longer exist, so only the
        // messages that resolve are returned to the provider.
        when(caseHoldClient.getCaseCommunicationIds("case-1"))
                .thenReturn(List.of("msg-1", "msg-missing"));

        MessageDocument msg1 = MessageDocument.builder()
                .id("msg-1")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build();

        when(ingestionClient.getMessages(anySet()))
                .thenReturn(List.of(msg1));

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .scope(ExportScope.CASE)
                        .caseId("case-1")
                        .build()
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("msg-1");
    }
}
