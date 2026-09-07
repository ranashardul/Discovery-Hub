package com.stown.ingestion.repository;

import com.stown.ingestion.domain.MessageDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface MessageRepository
        extends MongoRepository<MessageDocument, String> {

    Optional<MessageDocument> findByDeduplicationKey(
            String deduplicationKey
    );

    boolean existsByDeduplicationKey(String deduplicationKey);
}