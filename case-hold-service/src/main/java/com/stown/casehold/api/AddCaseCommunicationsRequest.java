package com.stown.casehold.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddCaseCommunicationsRequest(
        @NotEmpty List<@Valid CommunicationRef> communications,
        @NotBlank @Size(max = 255) String addedBy
) {
}
