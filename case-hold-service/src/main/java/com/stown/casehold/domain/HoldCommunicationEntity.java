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
 * A reference from a hold to a communication that the hold preserves.
 */
@Entity
@Table(name = "hold_communications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldCommunicationEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Column(name = "communication_id", nullable = false)
    private String communicationId;

    @Column(name = "communication_type")
    private String communicationType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
