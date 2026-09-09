package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Last known state of a legal hold as projected from
 * {@code case-hold.events}, owned by the case-hold service.
 *
 * <p>Two purposes: it makes duplicate event handling observable, and it lets a
 * stale {@code HOLD_CREATED} replay be ignored once a release has been
 * processed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hold_state")
public class HoldState {

    /** The holdId from the case-hold service. */
    @Id
    private String holdId;

    private String caseId;

    /** ACTIVE or RELEASED, as last observed. */
    private String status;

    /** COMMUNICATION or CRITERIA. */
    private String scope;

    /** Event ids already applied, so redelivery is a no-op. */
    private List<String> appliedEventIds;

    private long messagesAffected;

    private Instant firstSeenAt;
    private Instant lastEventAt;
}
