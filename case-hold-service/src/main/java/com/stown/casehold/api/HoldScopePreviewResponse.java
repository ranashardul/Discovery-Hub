package com.stown.casehold.api;

/**
 * How many communications a hold rule currently matches.
 *
 * <p>Advisory: the count is a snapshot taken from the search index, and the
 * hold is applied later against whatever matches at that point. It exists so a
 * reviewer does not commit to a rule without knowing whether it covers ten
 * messages or ten thousand.
 */
public record HoldScopePreviewResponse(long matchingCount) {
}
