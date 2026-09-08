package com.stown.casehold.repository;

import com.stown.casehold.domain.CaseEntity;
import com.stown.casehold.domain.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CaseRepository extends JpaRepository<CaseEntity, UUID> {

    List<CaseEntity> findAllByOrderByCreatedAtDesc();

    List<CaseEntity> findByStatusOrderByCreatedAtDesc(CaseStatus status);
}
