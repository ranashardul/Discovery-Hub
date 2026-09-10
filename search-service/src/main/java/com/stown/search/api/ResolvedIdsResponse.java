package com.stown.search.api;

import java.util.List;

/**
 * Every message id matching a set of criteria.
 *
 * <p>Backs "add all results to a case" without paging the whole result set
 * through the browser. {@code truncated} is true when the match count exceeded
 * the configured cap, so a caller can tell a complete answer from a partial
 * one rather than silently scoping a case to the first N matches.
 */
public record ResolvedIdsResponse(
        long total,
        boolean truncated,
        List<String> messageIds
) {
}
