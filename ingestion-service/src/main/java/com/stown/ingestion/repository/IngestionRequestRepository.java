package com.stown.ingestion.repository;

import com.stown.ingestion.domain.IngestionRequestDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface IngestionRequestRepository
        extends MongoRepository<IngestionRequestDocument, String> {

    Optional<IngestionRequestDocument> findByDeduplicationKey(String deduplicationKey);

    Optional<IngestionRequestDocument> findByExternalMessageId(String externalMessageId);
}
