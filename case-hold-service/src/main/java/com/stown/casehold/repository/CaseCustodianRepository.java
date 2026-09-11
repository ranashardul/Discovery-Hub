package com.stown.casehold.repository;

import com.stown.casehold.domain.CaseCustodianEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaseCustodianRepository extends JpaRepository<CaseCustodianEntity, UUID> {

    List<CaseCustodianEntity> findByCaseIdOrderByAddedAtAsc(UUID caseId);

    long countByCaseId(UUID caseId);

    boolean existsByCaseIdAndCustodianId(UUID caseId, String custodianId);

    void deleteByCaseIdAndCustodianId(UUID caseId, String custodianId);
}
