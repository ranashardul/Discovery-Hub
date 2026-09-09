package com.stown.casehold.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Message metadata resolved from the {@code messages} collection owned by
 * ingestion-service, attached to a communication reference so a reviewer sees
 * who sent what rather than a bare identifier.
 *
 * <p>{@code resolved} is false when the reference did not match any message —
 * either the ID is wrong, or the message store could not be reached. In that
 * case every other field is null and the reference is still returned, because
 * the case record itself remains valid.
 *
 * <p>{@code holdCount} and {@code dispositionStatus} are projected onto the
 * message by ingestion-service in response to the hold events this service
 * publishes. They are echoed here so a caller can confirm a hold was actually
 * enforced on the message data, not just recorded in PostgreSQL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommunicationDetail(
        boolean resolved,
        String sender,
        List<String> recipients,
        String subject,
        Instant messageTimestamp,
        String threadId,
        Integer attachmentCount,
        Integer holdCount,
        String dispositionStatus,
        Instant retentionUntil
) {

    /** Marker for a reference that could not be resolved to a message. */
    public static CommunicationDetail unresolved() {
        return new CommunicationDetail(
                false, null, null, null, null, null, null, null, null, null
        );
    }
}
