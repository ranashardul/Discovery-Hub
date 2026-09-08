package com.stown.casehold.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * In-memory representation of a criteria-based hold rule. Persisted to the
 * {@code holds} table as comma-separated text (participants, types) plus
 * timestamp columns (date range) by {@link com.stown.casehold.service.HoldService}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldCriteria {

    private List<String> participants;
    private List<String> communicationTypes;
    private Instant dateFrom;
    private Instant dateTo;

    /** True when at least one criterion has been supplied. */
    public boolean isEmpty() {
        return isBlank(participants) && isBlank(communicationTypes) && dateFrom == null && dateTo == null;
    }

    private static boolean isBlank(List<String> values) {
        return values == null || values.isEmpty();
    }
}
