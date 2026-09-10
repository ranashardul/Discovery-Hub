package com.stown.search.service;

import com.stown.search.api.SearchRequest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.time.format.DateTimeParseException;

public record SearchCriteria(
        String query,
        String communicationType,
        String sender,
        String recipient,
        List<String> participants,
        String threadId,
        String dispositionStatus,
        String holdId,
        Boolean onHold,
        Boolean hasAttachments,
        Instant after,
        Instant before,
        SearchSort sort,
        int from,
        int size
) {

    public static final int DEFAULT_SIZE = 20;

    /**
     * Normalises and validates raw request parameters. Blank optional filters
     * are dropped and {@code size} is capped.
     *
     * <p>{@code q} may be omitted when at least one filter is supplied, which
     * is what "every message in this thread" or "everything under this hold"
     * needs - questions with no text to match on. A request with neither text
     * nor filters is still rejected, because that is a request to page the
     * entire corpus and is far more likely to be a mistake than an intent.
     */
    public static SearchCriteria of(SearchRequest request, int maxSize) {
        String query = trimToNull(request.q());
        if (query == null && !hasAnyFilter(request)) {
            throw new InvalidSearchRequestException(
                    "q",
                    "Query parameter 'q' is required unless at least one filter is supplied"
            );
        }

        int resolvedFrom = request.from() == null ? 0 : request.from();
        if (resolvedFrom < 0) {
            throw new InvalidSearchRequestException("from", "Parameter 'from' must not be negative");
        }

        int resolvedSize = request.size() == null ? DEFAULT_SIZE : request.size();
        if (resolvedSize < 1) {
            throw new InvalidSearchRequestException("size", "Parameter 'size' must be at least 1");
        }
        if (resolvedSize > maxSize) {
            resolvedSize = maxSize;
        }

        Instant after = parseInstant("after", request.after());
        Instant before = parseInstant("before", request.before());
        if (after != null && before != null && after.isAfter(before)) {
            throw new InvalidSearchRequestException("after", "Parameter 'after' must not be later than 'before'");
        }

        return new SearchCriteria(
                query,
                trimToNull(request.communicationType()),
                trimToNull(request.sender()),
                trimToNull(request.recipient()),
                normaliseList(request.participants()),
                trimToNull(request.threadId()),
                trimToNull(request.dispositionStatus()),
                trimToNull(request.holdId()),
                request.onHold(),
                request.hasAttachments(),
                after,
                before,
                SearchSort.from(request.sort()),
                resolvedFrom,
                resolvedSize
        );
    }

    /**
     * Convenience factory for the common case of a plain full-text query.
     */
    public static SearchCriteria ofQuery(String query, int maxSize) {
        return of(SearchRequest.builder().q(query).build(), maxSize);
    }

    public boolean hasDateRange() {
        return after != null || before != null;
    }

    /** True when the caller supplied text to match on. */
    public boolean hasQuery() {
        return query != null;
    }

    private static boolean hasAnyFilter(SearchRequest request) {
        return trimToNull(request.communicationType()) != null
                || trimToNull(request.sender()) != null
                || trimToNull(request.recipient()) != null
                || !normaliseList(request.participants()).isEmpty()
                || trimToNull(request.threadId()) != null
                || trimToNull(request.dispositionStatus()) != null
                || trimToNull(request.holdId()) != null
                || request.onHold() != null
                || request.hasAttachments() != null
                || trimToNull(request.after()) != null
                || trimToNull(request.before()) != null;
    }

    private static Instant parseInstant(String field, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }

        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException exception) {
            throw new InvalidSearchRequestException(
                    field,
                    "Parameter '" + field + "' must be an ISO-8601 instant, for example 2026-09-08T03:00:00Z"
            );
        }
    }

    /** Drops null and blank entries, and de-duplicates, preserving order. */
    private static List<String> normaliseList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(SearchCriteria::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
