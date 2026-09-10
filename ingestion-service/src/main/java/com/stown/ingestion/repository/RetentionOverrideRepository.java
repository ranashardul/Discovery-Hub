package com.stown.ingestion.repository;

import com.stown.ingestion.domain.RetentionOverride;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RetentionOverrideRepository extends MongoRepository<RetentionOverride, String> {
}
