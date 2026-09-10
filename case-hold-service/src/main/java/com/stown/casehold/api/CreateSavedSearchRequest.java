package com.stown.casehold.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Saves a named search against a case.
 *
 * <p>{@code criteria} is an arbitrary filter map, stored as JSON and never
 * interpreted here. It is whatever the search API accepts; keeping it opaque
 * means a new search filter needs no change in this service.
 */
public record CreateSavedSearchRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Map<String, Object> criteria,
        @NotBlank @Size(max = 255) String createdBy
) {
}
