package com.stown.casehold.service;

import com.stown.casehold.api.CreateSavedSearchRequest;
import com.stown.casehold.api.SavedSearchResponse;
import com.stown.casehold.domain.CaseEntity;
import com.stown.casehold.domain.CaseStatus;
import com.stown.casehold.domain.SavedSearchEntity;
import com.stown.casehold.repository.CaseRepository;
import com.stown.casehold.repository.SavedSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedSearchServiceTest {

    private static final UUID CASE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private SavedSearchRepository savedSearchRepository;

    @Mock
    private CaseRepository caseRepository;

    private SavedSearchService service;

    @BeforeEach
    void setUp() {
        service = new SavedSearchService(
                savedSearchRepository,
                caseRepository,
                new ObjectMapper()
        );

        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(openCase()));
        when(savedSearchRepository.save(any(SavedSearchEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CaseEntity openCase() {
        CaseEntity entity = new CaseEntity();
        entity.setId(CASE_ID);
        entity.setStatus(CaseStatus.OPEN);
        return entity;
    }

    private CreateSavedSearchRequest request(String name) {
        return new CreateSavedSearchRequest(
                name,
                Map.of("q", "budget", "communicationType", "EMAIL"),
                "asritha"
        );
    }

    @Test
    void savesTheCriteriaAndReturnsThemDecoded() {
        SavedSearchResponse response = service.save(CASE_ID, request("Budget emails"));

        assertThat(response.name()).isEqualTo("Budget emails");
        assertThat(response.caseId()).isEqualTo(CASE_ID);
        assertThat(response.criteria())
                .containsEntry("q", "budget")
                .containsEntry("communicationType", "EMAIL");
    }

    /**
     * The criteria are stored as JSON so a new search filter needs no schema
     * change here. Whatever the client sent must round-trip unchanged.
     */
    @Test
    void persistsTheCriteriaAsJsonWithoutInterpretingThem() {
        service.save(CASE_ID, request("Anything"));

        ArgumentCaptor<SavedSearchEntity> captor = ArgumentCaptor.forClass(SavedSearchEntity.class);
        verify(savedSearchRepository).save(captor.capture());

        assertThat(captor.getValue().getCriteria())
                .contains("\"q\":\"budget\"")
                .contains("\"communicationType\":\"EMAIL\"");
    }

    @Test
    void trimsTheName() {
        assertThat(service.save(CASE_ID, request("  Padded  ")).name()).isEqualTo("Padded");
    }

    /**
     * Names are how a reviewer refers to a scope during a matter, so two
     * different filter sets must not share one.
     */
    @Test
    void refusesADuplicateNameOnTheSameCase() {
        when(savedSearchRepository.existsByCaseIdAndName(CASE_ID, "Budget emails")).thenReturn(true);

        assertThatThrownBy(() -> service.save(CASE_ID, request("Budget emails")))
                .isInstanceOf(SavedSearchNameTakenException.class)
                .hasMessageContaining("Budget emails");

        verify(savedSearchRepository, never()).save(any(SavedSearchEntity.class));
    }

    @Test
    void refusesToSaveAgainstAnArchivedCase() {
        CaseEntity archived = openCase();
        archived.setStatus(CaseStatus.ARCHIVED);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.save(CASE_ID, request("Too late")))
                .isInstanceOf(IllegalHoldStateException.class);
    }

    @Test
    void refusesToSaveAgainstACaseThatDoesNotExist() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(CASE_ID, request("Nowhere")))
                .isInstanceOf(CaseNotFoundException.class);
    }

    /**
     * One unreadable row must not make every other saved search on the case
     * unreachable, so decoding degrades to empty criteria rather than throwing.
     */
    @Test
    void degradesUnreadableCriteriaToAnEmptyMapRatherThanFailingTheList() {
        when(savedSearchRepository.findByCaseIdOrderByCreatedAtDesc(CASE_ID)).thenReturn(List.of(
                SavedSearchEntity.builder()
                        .id(UUID.randomUUID())
                        .caseId(CASE_ID)
                        .name("Corrupt")
                        .criteria("not json")
                        .createdBy("asritha")
                        .createdAt(Instant.now())
                        .build()
        ));

        List<SavedSearchResponse> searches = service.list(CASE_ID);

        assertThat(searches).hasSize(1);
        assertThat(searches.getFirst().criteria()).isEmpty();
    }

    @Test
    void deletingAnUnknownSavedSearchIsNotFound() {
        UUID id = UUID.randomUUID();
        when(savedSearchRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(SavedSearchNotFoundException.class);
    }

    @Test
    void listingForACaseThatDoesNotExistIsNotFound() {
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(CASE_ID))
                .isInstanceOf(CaseNotFoundException.class);
    }
}
