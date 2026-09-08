package com.stown.exportaudit.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;

@Data
public class ExportRequest {

    @NotBlank
    private String caseId;

    /** {@code CASE} or {@code LEGAL_HOLD}. Defaults to {@code CASE}. */
    private String scope;

    /** Hold ID, required when scope is {@code LEGAL_HOLD}. */
    private String holdId;

    /** Actor that requested the export, used for the audit trail. */
    private String requestedBy;

    // Optional evidence filters.
    private String communicationType;
    private String sender;
    private String threadId;
    private Instant fromTimestamp;
    private Instant toTimestamp;
}
