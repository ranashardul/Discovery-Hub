package com.stown.search.api;

public record ReindexResponse(
        long scanned,
        long indexed,
        long skipped,
        long failed,
        long elapsedMs
) {
}
