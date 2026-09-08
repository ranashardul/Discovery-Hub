package com.stown.search.service;

public record SearchCriteria(
        String query,
        String communicationType,
        String sender,
        String threadId,
        int from,
        int size
) {

    public static final int DEFAULT_SIZE = 20;

    /**
     * Normalises and validates raw request parameters. Blank optional filters
     * are dropped, {@code size} is capped and {@code q} is mandatory.
     */
    public static SearchCriteria of(
            String query,
            String communicationType,
            String sender,
            String threadId,
            Integer from,
            Integer size,
            int maxSize
    ) {
        if (query == null || query.isBlank()) {
            throw new InvalidSearchRequestException("q", "Query parameter 'q' is required and must not be blank");
        }

        int resolvedFrom = from == null ? 0 : from;
        if (resolvedFrom < 0) {
            throw new InvalidSearchRequestException("from", "Parameter 'from' must not be negative");
        }

        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedSize < 1) {
            throw new InvalidSearchRequestException("size", "Parameter 'size' must be at least 1");
        }
        if (resolvedSize > maxSize) {
            resolvedSize = maxSize;
        }

        return new SearchCriteria(
                query.trim(),
                trimToNull(communicationType),
                trimToNull(sender),
                trimToNull(threadId),
                resolvedFrom,
                resolvedSize
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
