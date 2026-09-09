package com.stown.search.service;

import com.stown.search.config.SearchProperties;
import com.stown.search.domain.MessageDocument;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.repository.MessageRepository;
import com.stown.search.repository.SearchIndexFailureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep deletes data, so the cases that matter most are the ones where it
 * must decline to act.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationJobOrphanSweepTest {

    @Mock
    private SearchIndexFailureRepository failureRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageIndexClient indexClient;

    @Mock
    private IndexingService indexingService;

    private SearchProperties properties;
    private ReconciliationJob job;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        properties.setReconcileBackfillEnabled(false);
        properties.setOrphanSweepBatchSize(3);

        job = new ReconciliationJob(
                failureRepository,
                messageRepository,
                indexClient,
                indexingService,
                properties
        );

        when(failureRepository.findByResolvedFalseAndAbandonedFalse(any())).thenReturn(List.of());
    }

    private MessageDocument message(String id) {
        return MessageDocument.builder().id(id).build();
    }

    @Test
    void deletesDocumentsWhoseMessageIsGone() throws IOException {
        when(indexClient.listMessageIds(isNull(), eq(3))).thenReturn(List.of("a", "b", "c"));
        // "b" is absent from MongoDB - disposed, but the event never arrived.
        when(messageRepository.findAllById(List.of("a", "b", "c")))
                .thenReturn(List.of(message("a"), message("c")));
        when(indexClient.delete("b")).thenReturn(true);

        job.reconcile();

        verify(indexClient).delete("b");
        verify(indexClient, never()).delete("a");
        verify(indexClient, never()).delete("c");
    }

    @Test
    void deletesNothingWhenEveryDocumentStillExists() throws IOException {
        when(indexClient.listMessageIds(isNull(), eq(3))).thenReturn(List.of("a", "b"));
        when(messageRepository.findAllById(List.of("a", "b")))
                .thenReturn(List.of(message("a"), message("b")));

        job.reconcile();

        verify(indexClient, never()).delete(anyString());
    }

    @Test
    void deletesNothingWhenTheMongoLookupFails() throws IOException {
        // A database error must never be read as "these messages were disposed".
        when(indexClient.listMessageIds(isNull(), eq(3))).thenReturn(List.of("a", "b"));
        when(messageRepository.findAllById(any(Iterable.class)))
                .thenThrow(new IllegalStateException("mongo unreachable"));

        job.reconcile();

        verify(indexClient, never()).delete(anyString());
    }

    @Test
    void deletesNothingWhenTheIndexCannotBeListed() throws IOException {
        when(indexClient.listMessageIds(isNull(), eq(3))).thenThrow(new IOException("elasticsearch down"));

        job.reconcile();

        verify(messageRepository, never()).findAllById(any(Iterable.class));
        verify(indexClient, never()).delete(anyString());
    }

    @Test
    void deletesNothingWhenTheSweepIsDisabled() throws IOException {
        properties.setOrphanSweepEnabled(false);

        job.reconcile();

        verify(indexClient, never()).listMessageIds(anyString(), anyInt());
        verify(indexClient, never()).delete(anyString());
    }

    @Test
    void advancesTheCursorAcrossCyclesSoTheWholeIndexIsCovered() throws IOException {
        when(indexClient.listMessageIds(isNull(), eq(3))).thenReturn(List.of("a", "b", "c"));
        when(indexClient.listMessageIds(eq("c"), eq(3))).thenReturn(List.of("d", "e", "f"));
        when(messageRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(message("a"), message("b"), message("c")))
                .thenReturn(List.of(message("d"), message("e"), message("f")));

        job.reconcile();
        job.reconcile();

        verify(indexClient).listMessageIds(isNull(), eq(3));
        verify(indexClient).listMessageIds(eq("c"), eq(3));
    }

    @Test
    void restartsFromTheBeginningAfterAShortPage() throws IOException {
        // Fewer results than the batch size means the end of the index.
        when(indexClient.listMessageIds(isNull(), eq(3))).thenReturn(List.of("a", "b"));
        when(messageRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(message("a"), message("b")));

        job.reconcile();
        job.reconcile();

        verify(indexClient, org.mockito.Mockito.times(2)).listMessageIds(isNull(), eq(3));
    }

    @Test
    void keepsSweepingWhenOneDeletionFails() throws IOException {
        when(indexClient.listMessageIds(isNull(), eq(3))).thenReturn(List.of("a", "b", "c"));
        when(messageRepository.findAllById(List.of("a", "b", "c"))).thenReturn(List.of());
        when(indexClient.delete("a")).thenThrow(new IOException("transient"));
        when(indexClient.delete("b")).thenReturn(true);
        when(indexClient.delete("c")).thenReturn(true);

        job.reconcile();

        assertThat(true).isTrue();
        verify(indexClient).delete("b");
        verify(indexClient).delete("c");
    }
}
