package com.stown.exportaudit.config;

import com.stown.exportaudit.domain.AuditEventDocument;
import com.stown.exportaudit.domain.ExportJobDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Logs the effective MongoDB database and the indexes on the export and audit
 * collections so that database-selection and deduplication-index problems are
 * visible in the startup logs. The {@code messages} collection is owned by the
 * ingestion service and is only read here, so it is logged without index
 * validation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoStartupCheck {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void logMongoState() {
        log.info("MongoDB ready database={}", mongoTemplate.getDb().getName());

        logOwnedCollection(ExportJobDocument.class);
        logOwnedCollection(AuditEventDocument.class);
    }

    private void logOwnedCollection(Class<?> documentType) {
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
    }
}
