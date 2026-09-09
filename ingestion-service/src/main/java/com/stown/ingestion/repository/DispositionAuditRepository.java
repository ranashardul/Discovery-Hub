package com.stown.ingestion.repository;

import com.stown.ingestion.domain.DispositionAudit;
import com.stown.ingestion.domain.DispositionOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DispositionAuditRepository
        extends MongoRepository<DispositionAudit, String> {

    Optional<DispositionAudit> findFirstByMessageIdOrderByDecidedAtDesc(String messageId);

    /** Records whose object purge or event publication never completed. */
    List<DispositionAudit> findByOutcomeAndObjectsPurgedFalse(
            DispositionOutcome outcome,
            Pageable pageable
    );

    List<DispositionAudit> findByOutcome(DispositionOutcome outcome, Pageable pageable);

    long countByOutcome(DispositionOutcome outcome);
}
