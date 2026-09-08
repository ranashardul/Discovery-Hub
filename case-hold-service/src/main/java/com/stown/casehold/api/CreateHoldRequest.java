package com.stown.casehold.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Creates a hold on a case. Supply exactly one of:
 *
 * <ul>
 *   <li>{@code communications} — a {@link HoldScope#COMMUNICATION} hold that
 *       preserves the listed communication IDs explicitly.</li>
 *   <li>{@code criteria} — a {@link HoldScope#CRITERIA} hold that preserves
 *       every communication matching the rule.</li>
 * </ul>
 */
public record CreateHoldRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        @Size(max = 4000) String reason,
        @NotBlank @Size(max = 255) String createdBy,
        List<@Valid CommunicationRef> communications,
        @Valid HoldCriteriaRequest criteria
) {
}
