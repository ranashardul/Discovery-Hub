package com.stown.search.api;

import java.util.List;

public record SearchResponse(
        String query,
        long total,
        int from,
        int size,
        String sort,
        long tookMillis,
        List<SearchResultItem> results
) {
}
