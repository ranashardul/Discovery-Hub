package com.stown.search.api;

import java.util.List;

public record SearchResponse(
        String query,
        long total,
        int from,
        int size,
        long tookMillis,
        List<SearchResultItem> results
) {
}
