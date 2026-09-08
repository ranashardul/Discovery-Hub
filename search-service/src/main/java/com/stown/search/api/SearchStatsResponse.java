package com.stown.search.api;

public record SearchStatsResponse(
        long indexedCount,
        String index,
        long pendingFailures
) {
}
