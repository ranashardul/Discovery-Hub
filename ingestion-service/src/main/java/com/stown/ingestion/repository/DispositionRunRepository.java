package com.stown.ingestion.repository;

import com.stown.ingestion.domain.DispositionRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DispositionRunRepository
        extends MongoRepository<DispositionRun, String> {

    List<DispositionRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
