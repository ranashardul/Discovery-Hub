package com.stown.casehold.api;

import jakarta.validation.constraints.NotBlank;

public record AddCaseCustodianRequest(
        @NotBlank
        String custodianId,
        @NotBlank
        String addedBy
) {
}
