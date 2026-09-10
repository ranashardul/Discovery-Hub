package com.stown.casehold.repository;

import com.stown.casehold.domain.SavedSearchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SavedSearchRepository extends JpaRepository<SavedSearchEntity, UUID> {

    List<SavedSearchEntity> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    boolean existsByCaseIdAndName(UUID caseId, String name);
}
