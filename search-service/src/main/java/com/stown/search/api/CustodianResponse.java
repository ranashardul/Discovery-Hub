package com.stown.search.api;

/**
 * A custodian derived from the index rather than from a directory.
 *
 * <p>No service models custodians as entities, so the only honest answer to
 * "who is in this archive" is the set of distinct senders actually present.
 * {@code messageCount} is how many indexed messages they sent — not how many
 * they appear on, since a recipient-side count would double the totals.
 */
public record CustodianResponse(
        String name,
        long messageCount
) {
}
