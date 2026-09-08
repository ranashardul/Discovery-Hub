package com.stown.search.service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The orderings a caller may request. Relevance is the default; the timestamp
 * orderings exist because a reviewer working a custodian's mailbox needs
 * chronological order rather than a relevance ranking.
 */
public enum SearchSort {

    RELEVANCE("relevance"),
    NEWEST("newest"),
    OLDEST("oldest");

    private final String value;

    SearchSort(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SearchSort from(String raw) {
        if (raw == null || raw.isBlank()) {
            return RELEVANCE;
        }

        String normalised = raw.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(normalised))
                .findFirst()
                .orElseThrow(() -> new InvalidSearchRequestException(
                        "sort",
                        "Parameter 'sort' must be one of " + supportedValues()
                ));
    }

    private static String supportedValues() {
        return Arrays.stream(values())
                .map(SearchSort::value)
                .collect(Collectors.joining(", "));
    }
}
