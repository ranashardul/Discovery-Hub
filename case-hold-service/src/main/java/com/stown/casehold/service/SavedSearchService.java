package com.stown.casehold.service;

import com.stown.casehold.api.CreateSavedSearchRequest;
import com.stown.casehold.api.SavedSearchResponse;
import com.stown.casehold.domain.CaseEntity;
import com.stown.casehold.domain.CaseStatus;
import com.stown.casehold.domain.SavedSearchEntity;
import com.stown.casehold.repository.CaseRepository;
import com.stown.casehold.repository.SavedSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Named searches scoped to a case.
 *
 * <p>Lives here rather than in the search service because a saved search is a
 * case artefact: it records how a reviewer scoped the evidence for a matter,
 * and it should be removed with the case rather than outliving it in another
 * store.
 *
 * <p>The criteria are persisted as opaque JSON. This service never reads them;
 * the client replays them against the search API. That keeps a new search
 * filter from needing a migration here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedSearchService {

    private final SavedSearchRepository savedSearchRepository;
    private final CaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SavedSearchResponse> list(UUID caseId) {
        requireCase(caseId);

        return savedSearchRepository.findByCaseIdOrderByCreatedAtDesc(caseId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SavedSearchResponse save(UUID caseId, CreateSavedSearchRequest request) {
        CaseEntity entity = requireCase(caseId);

        if (entity.getStatus() == CaseStatus.ARCHIVED) {
            throw new IllegalHoldStateException(
                    "Cannot add a saved search to an archived case: " + caseId
            );
        }

        String name = request.name().trim();

        // The unique constraint would catch this, but a named 409 tells the
        // reviewer which name collided instead of surfacing a driver error.
        if (savedSearchRepository.existsByCaseIdAndName(caseId, name)) {
            throw new SavedSearchNameTakenException(name);
        }

        SavedSearchEntity saved = savedSearchRepository.save(SavedSearchEntity.builder()
                .id(UUID.randomUUID())
                .caseId(caseId)
                .name(name)
                .criteria(encode(request.criteria()))
                .createdBy(request.createdBy())
                .createdAt(Instant.now())
                .build());

        log.info(
                "Saved search id={} caseId={} name=\"{}\" by {}",
                saved.getId(),
                caseId,
                name,
                request.createdBy()
        );

        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID savedSearchId) {
        SavedSearchEntity entity = savedSearchRepository.findById(savedSearchId)
                .orElseThrow(() -> new SavedSearchNotFoundException(savedSearchId.toString()));

        savedSearchRepository.delete(entity);

        log.info("Deleted saved search id={} caseId={}", savedSearchId, entity.getCaseId());
    }

    private CaseEntity requireCase(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CaseNotFoundException(caseId.toString()));
    }

    private String encode(Map<String, Object> criteria) {
        return objectMapper.writeValueAsString(criteria);
    }

    /**
     * Decodes stored criteria, degrading to an empty map rather than failing
     * the whole list. A single unreadable row must not make every other saved
     * search on the case unreachable.
     */
    private Map<String, Object> decode(SavedSearchEntity entity) {
        try {
            return objectMapper.readValue(entity.getCriteria(), new TypeReference<>() {
            });
        } catch (Exception exception) {
            log.warn(
                    "Could not decode criteria for saved search id={} reason={}",
                    entity.getId(),
                    exception.getMessage()
            );
            return Map.of();
        }
    }

    private SavedSearchResponse toResponse(SavedSearchEntity entity) {
        return new SavedSearchResponse(
                entity.getId(),
                entity.getCaseId(),
                entity.getName(),
                decode(entity),
                entity.getCreatedBy(),
                entity.getCreatedAt()
        );
    }
}
