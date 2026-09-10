package com.stown.casehold.service;

import com.stown.casehold.api.HoldCriteriaRequest;
import com.stown.casehold.domain.HoldCriteria;
import com.stown.casehold.messaging.EventOutboxWriter;
import com.stown.casehold.repository.CaseRepository;
import com.stown.casehold.repository.HoldCommunicationRepository;
import com.stown.casehold.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hold is a legal instrument and over-scoping one preserves material the
 * matter has no claim to, so a reviewer needs the size of a rule before
 * committing to it.
 */
@ExtendWith(MockitoExtension.class)
class HoldScopePreviewTest {

    @Mock
    private HoldRepository holdRepository;

    @Mock
    private HoldCommunicationRepository holdCommunicationRepository;

    @Mock
    private CaseRepository caseRepository;

    @Mock
    private MessageLookupService messageLookupService;

    @Mock
    private EventOutboxWriter outbox;

    @Mock
    private SearchClient searchClient;

    private HoldService service;

    @BeforeEach
    void setUp() {
        service = new HoldService(
                holdRepository,
                holdCommunicationRepository,
                caseRepository,
                messageLookupService,
                outbox,
                searchClient
        );
    }

    @Test
    void returnsTheCountTheSearchServiceReports() {
        when(searchClient.countMatching(any(HoldCriteria.class))).thenReturn(1234L);

        HoldCriteriaRequest request = new HoldCriteriaRequest(
                List.of("alice@stown.com"), null, null, null
        );

        assertThat(service.previewScope(request).matchingCount()).isEqualTo(1234L);
    }

    @Test
    void passesTheNormalisedCriteriaThrough() {
        when(searchClient.countMatching(any(HoldCriteria.class))).thenReturn(0L);

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        service.previewScope(new HoldCriteriaRequest(
                // Blanks and duplicates are stripped before the rule is used,
                // exactly as they are when a real hold is placed.
                List.of("alice@stown.com", "", "alice@stown.com"),
                List.of("EMAIL"),
                from,
                null
        ));

        ArgumentCaptor<HoldCriteria> captor = ArgumentCaptor.forClass(HoldCriteria.class);
        verify(searchClient).countMatching(captor.capture());

        assertThat(captor.getValue().getParticipants()).containsExactly("alice@stown.com");
        assertThat(captor.getValue().getCommunicationTypes()).containsExactly("EMAIL");
        assertThat(captor.getValue().getDateFrom()).isEqualTo(from);
    }

    /**
     * A preview that accepts criteria the hold endpoint would reject is a
     * misleading preview, so both run the same validation.
     */
    @Test
    void refusesAnEmptyRuleJustAsPlacingAHoldWould() {
        assertThatThrownBy(() -> service.previewScope(
                new HoldCriteriaRequest(null, null, null, null)
        )).isInstanceOf(IllegalArgumentException.class);

        verify(searchClient, never()).countMatching(any());
    }

    @Test
    void refusesAnInvertedDateRange() {
        assertThatThrownBy(() -> service.previewScope(new HoldCriteriaRequest(
                null,
                null,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        ))).isInstanceOf(IllegalArgumentException.class);

        verify(searchClient, never()).countMatching(any());
    }

    @Test
    void refusesAMissingRule() {
        assertThatThrownBy(() -> service.previewScope(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The preview reads nothing from the case store; it is archive-wide. */
    @Test
    void doesNotTouchTheCaseOrHoldTables() {
        when(searchClient.countMatching(any(HoldCriteria.class))).thenReturn(7L);

        service.previewScope(new HoldCriteriaRequest(List.of("bob@stown.com"), null, null, null));

        verify(caseRepository, never()).findById(any());
        verify(holdRepository, never()).save(any());
        verify(outbox, never()).write(any(), any(), any());
    }
}
