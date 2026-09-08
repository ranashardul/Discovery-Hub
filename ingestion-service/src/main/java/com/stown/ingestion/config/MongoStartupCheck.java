package com.stown.ingestion.config;

import com.stown.ingestion.domain.IngestionRequestDocument;
import com.stown.ingestion.domain.MessageDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Logs the effective MongoDB database and the indexes on the ingestion
 * collections so that database-selection and deduplication-index problems are
 * visible in the startup logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoStartupCheck {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void logMongoState() {
        log.info("MongoDB ready database={}", mongoTemplate.getDb().getName());

        logCollection(MessageDocument.class);
        logCollection(IngestionRequestDocument.class);
    }

    private void logCollection(Class<?> documentType) {
        String collection = mongoTemplate.getCollectionName(documentType);

        String indexes = mongoTemplate
                .indexOps(documentType)
                .getIndexInfo()
                .stream()
                .map(index -> index.getName() + (index.isUnique() ? " (unique)" : ""))
                .collect(Collectors.joining(", "));

        log.info(
                "Collection {} documents={} indexes=[{}]",
                collection,
                mongoTemplate.getCollection(collection).countDocuments(),
                indexes
        );

        if (!indexes.contains("(unique)")) {
            log.warn(
                    "No unique index found on {} - duplicate keys are not prevented at the"
                            + " database level",
                    collection
            );
        }
    }
}
