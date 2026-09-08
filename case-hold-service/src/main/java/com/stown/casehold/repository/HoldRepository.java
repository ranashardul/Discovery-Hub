package com.stown.casehold.repository;

import com.stown.casehold.domain.HoldEntity;
import com.stown.casehold.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<HoldEntity, UUID> {

    List<HoldEntity> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    long countByCaseIdAndStatus(UUID caseId, HoldStatus status);
}
