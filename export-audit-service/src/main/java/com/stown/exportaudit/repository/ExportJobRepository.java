package com.stown.exportaudit.repository;

import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ExportJobRepository
        extends MongoRepository<ExportJobDocument, String> {

    List<ExportJobDocument> findByCaseIdOrderByCreatedAtDesc(String caseId);

    List<ExportJobDocument> findByStatus(ExportStatus status);
}
