package com.stown.exportaudit.domain;

/**
 * Catalogue of auditable actions. External services may submit additional
 * action strings through the audit API, so consumers must treat the value as
 * free-form text and use this enum only for the events this service emits.
 */
public enum AuditAction {

    EXPORT_REQUESTED,
    EXPORT_STARTED,
    EXPORT_COMPLETED,
    EXPORT_FAILED,
    EXPORT_RETRY_REQUESTED,
    EXPORT_DOWNLOADED,
    EXPORT_VERIFIED,

    EVIDENCE_ADDED,
    EVIDENCE_REMOVED,
    HOLD_PLACED,
    HOLD_RELEASED,
    CASE_CREATED,
    CASE_UPDATED,
    CASE_STATUS_CHANGED,
    SEARCH_EXECUTED,
    CUSTODIAN_ADDED,
    DISPOSITION_RUN
}
