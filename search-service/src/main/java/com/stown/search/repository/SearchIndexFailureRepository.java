package com.stown.search.repository;

import com.stown.search.domain.SearchIndexFailure;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SearchIndexFailureRepository
        extends MongoRepository<SearchIndexFailure, String> {

    List<SearchIndexFailure> findByResolvedFalseAndAbandonedFalse(Pageable pageable);

    long countByResolvedFalseAndAbandonedFalse();

    long countByAbandonedTrue();
}
