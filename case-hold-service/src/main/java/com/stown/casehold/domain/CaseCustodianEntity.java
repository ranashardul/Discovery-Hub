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
 * A person attached as a custodian to a case. Only the identity is stored:
 * the archive is the source of truth for display names or departments.
 */
@Entity
@Table(name = "case_custodians")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseCustodianEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "custodian_id", nullable = false)
    private String custodianId;

    @Column(name = "added_by", nullable = false)
    private String addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;
}
