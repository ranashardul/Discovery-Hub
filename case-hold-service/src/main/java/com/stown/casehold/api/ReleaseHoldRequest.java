package com.stown.casehold.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReleaseHoldRequest(
        @NotBlank @Size(max = 255) String releasedBy
) {
}
