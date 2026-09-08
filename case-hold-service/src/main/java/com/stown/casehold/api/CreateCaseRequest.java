package com.stown.casehold.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCaseRequest(
        @NotBlank @Size(max = 255) String caseName,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 255) String createdBy
) {
}
