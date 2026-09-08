package com.stown.casehold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "holds")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "reason")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private HoldStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, updatable = false)
    private HoldScope scope;

    // --- Criteria-based hold rules (null when scope = COMMUNICATION) --------

    /** Comma-separated participant addresses, or null. */
    @Column(name = "criteria_participants")
    private String criteriaParticipants;

    /** Comma-separated communication types, or null. */
    @Column(name = "criteria_communication_types")
    private String criteriaCommunicationTypes;

    @Column(name = "criteria_from_date")
    private Instant criteriaFromDate;

    @Column(name = "criteria_to_date")
    private Instant criteriaToDate;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_by")
    private String releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;
}
