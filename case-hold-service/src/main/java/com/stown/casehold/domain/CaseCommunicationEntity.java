package com.stown.casehold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A reference from a case to a communication owned by the ingestion/search
 * data layer. Only the identifier (and optionally the type) is stored here.
 */
@Entity
@Table(name = "case_communications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseCommunicationEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "communication_id", nullable = false)
    private String communicationId;

    @Column(name = "communication_type")
    private String communicationType;

    @Column(name = "added_by")
    private String addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;
}
