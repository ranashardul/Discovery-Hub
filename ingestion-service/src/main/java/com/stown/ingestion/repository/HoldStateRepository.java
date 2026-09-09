package com.stown.ingestion.repository;

import com.stown.ingestion.domain.HoldState;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HoldStateRepository extends MongoRepository<HoldState, String> {
}
