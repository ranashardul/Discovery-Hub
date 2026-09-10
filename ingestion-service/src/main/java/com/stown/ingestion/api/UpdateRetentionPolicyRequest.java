package com.stown.ingestion.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Sets the retention period for a communication type.
 *
 * <p>Expressed in minutes rather than as a duration string so a caller cannot
 * get the units wrong in a way that silently means something else — an
 * unparseable duration would be rejected, but "P30" quietly meaning 30 days
 * when 30 minutes was intended would destroy data.
 */
public record UpdateRetentionPolicyRequest(
        @NotNull @Positive Long retentionMinutes,
        @Size(max = 255) String updatedBy
) {
}
