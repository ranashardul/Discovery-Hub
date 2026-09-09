package com.stown.ingestion.repository;

import com.stown.ingestion.domain.MessageDocument;
import com.stown.ingestion.domain.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository
        extends MongoRepository<MessageDocument, String> {

    Optional<MessageDocument> findByDeduplicationKey(String deduplicationKey);

    Optional<MessageDocument> findByExternalMessageId(String externalMessageId);

    boolean existsByDeduplicationKey(String deduplicationKey);

    List<MessageDocument> findByOutboxStatus(OutboxStatus outboxStatus, Pageable pageable);

    long countByOutboxStatus(OutboxStatus outboxStatus);

    /** Disposition candidates: retention has expired. Holds are checked separately. */
    List<MessageDocument> findByRetentionUntilLessThanEqual(Instant cutoff, Pageable pageable);

    long countByRetentionUntilLessThanEqual(Instant cutoff);

    long countByHoldCountGreaterThan(int threshold);
}
