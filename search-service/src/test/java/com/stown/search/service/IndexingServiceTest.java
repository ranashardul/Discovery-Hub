package com.stown.search.service;

import com.stown.search.config.SearchProperties;
import com.stown.search.domain.MessageDocument;
import com.stown.search.domain.SearchIndexFailure;
import com.stown.search.index.MessageIndexClient;
import com.stown.search.index.SearchDocumentMapper;
import com.stown.search.repository.MessageRepository;
import com.stown.search.repository.SearchIndexFailureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexingServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SearchIndexFailureRepository failureRepository;

    @Mock
    private MessageIndexClient indexClient;

    private IndexingService indexingService;
    private SearchProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SearchProperties();
        indexingService = new IndexingService(
                messageRepository,
                failureRepository,
                new SearchDocumentMapper(),
                indexClient,
                properties
        );
    }

    // ---- disposal ----

    @Test
    void removesTheDocumentAndItsFailureLedgerEntry() throws IOException {
        when(indexClient.delete("msg-1")).thenReturn(true);

        indexingService.removeMessage("msg-1", "evt-1", "RETENTION");

        verify(indexClient).delete("msg-1");
        // The message no longer exists in MongoDB, so a lingering failure entry
        // could never be resolved and would be retried forever.
        verify(failureRepository).deleteById("msg-1");
    }

    @Test
    void treatsRemovalOfAnAbsentDocumentAsSuccess() throws IOException {
        // message.disposed is delivered at least once, so a redelivery must not
        // fail the listener and push a valid event to the dead letter topic.
        when(indexClient.delete("msg-1")).thenReturn(false);

        indexingService.removeMessage("msg-1", "evt-1", "MANUAL");

        verify(failureRepository).deleteById("msg-1");
    }

    @Test
    void propagatesRemovalFailureSoTheEventIsRetried() throws IOException {
        doThrow(new IOException("elasticsearch down")).when(indexClient).delete("msg-1");

        assertThatThrownBy(() -> indexingService.removeMessage("msg-1", "evt-1", "RETENTION"))
                .isInstanceOf(RuntimeException.class);

        verify(failureRepository, never()).deleteById(anyString());
    }

    // ---- retry cap ----

    @Test
    void abandonsAFailureOnceTheAttemptCapIsReached() throws IOException {
        properties.setFailureMaxAttempts(3);
        when(messageRepository.findById("msg-1")).thenReturn(Optional.empty());
        when(failureRepository.findById("msg-1")).thenReturn(Optional.of(SearchIndexFailure.builder()
                .id("msg-1")
                .messageId("msg-1")
                .attempts(2)
                .build()));

        assertThatThrownBy(() -> indexingService.indexMessage("msg-1", "evt-1"))
                .isInstanceOf(MessageNotFoundException.class);

        ArgumentCaptor<SearchIndexFailure> captor = ArgumentCaptor.forClass(SearchIndexFailure.class);
        verify(failureRepository).save(captor.capture());

        assertThat(captor.getValue().getAttempts()).isEqualTo(3);
        assertThat(captor.getValue().isAbandoned()).isTrue();
        assertThat(captor.getValue().getAbandonedAt()).isNotNull();
    }

    @Test
    void keepsRetryingBelowTheCap() {
        properties.setFailureMaxAttempts(5);
        when(messageRepository.findById("msg-1")).thenReturn(Optional.empty());
        when(failureRepository.findById("msg-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> indexingService.indexMessage("msg-1", "evt-1"))
                .isInstanceOf(MessageNotFoundException.class);

        ArgumentCaptor<SearchIndexFailure> captor = ArgumentCaptor.forClass(SearchIndexFailure.class);
        verify(failureRepository).save(captor.capture());

        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
        assertThat(captor.getValue().isAbandoned()).isFalse();
    }

    @Test
    void neverAbandonsWhenTheCapIsDisabled() {
        properties.setFailureMaxAttempts(0);
        when(messageRepository.findById("msg-1")).thenReturn(Optional.empty());
        when(failureRepository.findById("msg-1")).thenReturn(Optional.of(SearchIndexFailure.builder()
                .id("msg-1")
                .messageId("msg-1")
                .attempts(9_999)
                .build()));

        assertThatThrownBy(() -> indexingService.indexMessage("msg-1", "evt-1"))
                .isInstanceOf(MessageNotFoundException.class);

        ArgumentCaptor<SearchIndexFailure> captor = ArgumentCaptor.forClass(SearchIndexFailure.class);
        verify(failureRepository).save(captor.capture());

        assertThat(captor.getValue().isAbandoned()).isFalse();
    }

    @Test
    void clearsAbandonmentWhenIndexingLaterSucceeds() throws IOException {
        when(messageRepository.findById("msg-1")).thenReturn(Optional.of(MessageDocument.builder()
                .id("msg-1")
                .subject("Recovered")
                .build()));
        when(failureRepository.findById("msg-1")).thenReturn(Optional.of(SearchIndexFailure.builder()
                .id("msg-1")
                .messageId("msg-1")
                .attempts(10)
                .abandoned(true)
                .build()));

        indexingService.indexMessage("msg-1", "evt-1");

        verify(indexClient).index(any());

        ArgumentCaptor<SearchIndexFailure> captor = ArgumentCaptor.forClass(SearchIndexFailure.class);
        verify(failureRepository).save(captor.capture());

        assertThat(captor.getValue().isResolved()).isTrue();
        assertThat(captor.getValue().isAbandoned()).isFalse();
    }
}
