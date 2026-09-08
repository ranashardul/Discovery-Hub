package com.stown.search.api;

import lombok.Builder;

/**
 * Raw, unvalidated query-string parameters for {@code GET /api/search}.
 *
 * <p>Every field is nullable and untyped on purpose: binding stays lenient so
 * that malformed input is rejected by {@link com.stown.search.service.SearchCriteria}
 * with a field-attributed {@code 400} rather than by Spring's converters with a
 * generic one.
 */
@Builder
public record SearchRequest(
        String q,
        String communicationType,
        String sender,
        String recipient,
        String threadId,
        String dispositionStatus,
        Boolean onHold,
        Boolean hasAttachments,
        String after,
        String before,
        String sort,
        Integer from,
        Integer size
) {
}
