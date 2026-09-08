package com.stown.casehold.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a case. Any of {@code caseName}, {@code description} and
 * {@code status} may be supplied; at least one must be. {@code updatedBy} is
 * always required for audit and the {@code CASE_UPDATED} event.
 */
public record UpdateCaseRequest(
        @Size(max = 255) String caseName,
        String description,
        String status,
        @NotBlank @Size(max = 255) String updatedBy
) {
}
