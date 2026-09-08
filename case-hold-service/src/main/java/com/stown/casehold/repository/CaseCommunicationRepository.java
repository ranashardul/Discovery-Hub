package com.stown.casehold.repository;

import com.stown.casehold.domain.CaseCommunicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaseCommunicationRepository extends JpaRepository<CaseCommunicationEntity, UUID> {

    List<CaseCommunicationEntity> findByCaseIdOrderByAddedAtAsc(UUID caseId);

    long countByCaseId(UUID caseId);

    boolean existsByCaseIdAndCommunicationId(UUID caseId, String communicationId);
}
