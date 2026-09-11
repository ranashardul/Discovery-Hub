package com.stown.exportaudit.service;

import java.util.Map;

/**
 * A case-level audit report rendered in both forms the export package needs:
 * {@code text} for a reviewer to read and {@code data} for a machine to parse.
 *
 * <p>Both are produced from the same gathered facts in a single pass, so the
 * human-readable narrative and the structured record can never disagree.
 */
public record CaseAuditReport(String text, Map<String, Object> data) {
}
