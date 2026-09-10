package com.stown.exportaudit.api;

import java.util.List;

/**
 * A page of audit entries with the total number matching the filter.
 *
 * <p>The total is what makes the trail usable as evidence: a reviewer needs to
 * know they are looking at 20 of 4,318 entries, not just that there are 20 on
 * this screen.
 */
public record AuditPageResponse(
        List<AuditEventResponse> entries,
        long total,
        int page,
        int size
) {
}
