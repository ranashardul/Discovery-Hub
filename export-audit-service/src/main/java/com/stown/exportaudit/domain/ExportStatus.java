package com.stown.exportaudit.domain;

/**
 * Lifecycle of an export job, mirroring the states described in
 * {@code Export-Audit-services.md}.
 */
public enum ExportStatus {

    /** Job created and waiting for the worker to pick it up. */
    QUEUED,

    /** Worker is assembling the package. */
    RUNNING,

    /** Package generated, stored and ready to download. */
    COMPLETED,

    /** Generation failed; the job can be retried. */
    FAILED
}
