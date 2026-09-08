package com.stown.casehold.repository;

import com.stown.casehold.domain.HoldCommunicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HoldCommunicationRepository extends JpaRepository<HoldCommunicationEntity, UUID> {

    List<HoldCommunicationEntity> findByHoldIdOrderByCreatedAtAsc(UUID holdId);

    long countByHoldId(UUID holdId);

    boolean existsByHoldIdAndCommunicationId(UUID holdId, String communicationId);
}
