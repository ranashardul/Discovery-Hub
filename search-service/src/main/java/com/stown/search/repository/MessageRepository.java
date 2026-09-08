package com.stown.search.repository;

import com.stown.search.domain.MessageDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MessageRepository
        extends MongoRepository<MessageDocument, String> {
}
