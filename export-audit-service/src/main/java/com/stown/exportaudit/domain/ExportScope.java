package com.stown.exportaudit.domain;

/**
 * What an export package should cover. A {@code CASE} export assembles the
 * evidence items that belong to a case, while a {@code LEGAL_HOLD} export
 * assembles everything currently under a hold scope. Both use the same filter
 * criteria; the scope records the originating intent for the audit trail.
 */
public enum ExportScope {

    CASE,
    LEGAL_HOLD
}
