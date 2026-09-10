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
 * A named search scoped to a case: how a reviewer narrowed the archive for
 * this matter.
 *
 * <p>{@code criteria} holds the filter set as opaque JSON. This service never
 * interprets it — it stores what the client sent and hands the same thing
 * back, to be replayed against the search API. Modelling each filter as a
 * column would tie this schema to the search service's query parameters, so
 * adding a filter there would require a migration here.
 */
@Entity
@Table(name = "saved_searches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedSearchEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "criteria", nullable = false, columnDefinition = "text")
    private String criteria;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
