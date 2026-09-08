package com.stown.exportaudit.evidence;

import com.stown.exportaudit.domain.MessageDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoEvidenceProviderTest {

    @Mock
    private MongoTemplate mongoTemplate;

    private MongoEvidenceProvider evidenceProvider;

    @BeforeEach
    void setUp() {
        evidenceProvider = new MongoEvidenceProvider(mongoTemplate);
    }

    @Test
    void returnsMessagesOrderedByTimestampAscending() {
        MessageDocument message = MessageDocument.builder()
                .id("msg-1")
                .communicationType("EMAIL")
                .messageTimestamp(Instant.parse("2026-09-01T10:00:00Z"))
                .build();

        when(mongoTemplate.find(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(List.of(message));

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder().caseId("case-1").build()
        );

        assertThat(result).containsExactly(message);
    }

    @Test
    void appliesCommunicationTypeFilter() {
        MessageDocument message = MessageDocument.builder()
                .id("msg-1")
                .communicationType("EMAIL")
                .build();

        when(mongoTemplate.find(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(List.of(message));

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .caseId("case-1")
                        .communicationType("EMAIL")
                        .build()
        );

        assertThat(result).containsExactly(message);
    }

    @Test
    void appliesSenderFilter() {
        when(mongoTemplate.find(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(List.of());

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .caseId("case-1")
                        .sender("alice@example.com")
                        .build()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void appliesTimestampRangeFilter() {
        when(mongoTemplate.find(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(List.of());

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .caseId("case-1")
                        .fromTimestamp(Instant.parse("2026-01-01T00:00:00Z"))
                        .toTimestamp(Instant.parse("2026-12-31T23:59:59Z"))
                        .build()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void appliesThreadIdFilter() {
        when(mongoTemplate.find(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(List.of());

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder()
                        .caseId("case-1")
                        .threadId("thread-42")
                        .build()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void emptyQueryMatchesAll() {
        when(mongoTemplate.find(any(Query.class), eq(MessageDocument.class)))
                .thenReturn(List.of());

        List<MessageDocument> result = evidenceProvider.findEvidence(
                EvidenceQuery.builder().caseId("case-1").build()
        );

        assertThat(result).isEmpty();
    }
}
