package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Append-only record of one disposition decision for one message.
 *
 * <p>Written <em>before</em> the message is deleted, carrying the object keys,
 * so that a crash between the MongoDB delete and the object purge cannot lose
 * track of the binaries. It is also the evidence required by FR-5.3: what was
 * deleted, what was skipped because of a hold, and when.
 *
 * <p>No API updates or deletes these records.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "disposition_audit")
public class DispositionAudit {

    @Id
    private String id;

    @Indexed(name = "audit_messageId_idx")
    private String messageId;

    private String runId;
    private String externalMessageId;
    private String communicationType;

    @Indexed(name = "audit_outcome_idx")
    private DispositionOutcome outcome;

    /** RETENTION or MANUAL. */
    private String reason;

    private Instant retentionUntil;
    private int holdCountAtDecision;
    private List<String> holdIdsAtDecision;

    /** Object keys to purge, captured before the document is removed. */
    private List<String> s3Keys;
    private String s3Bucket;
    private boolean objectsPurged;

    private boolean eventPublished;

    private String lastError;
    private int attempts;

    private Instant decidedAt;
    private Instant completedAt;
}
