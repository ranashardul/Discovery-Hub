package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Summary of a single disposition run, satisfying FR-5.3: every run records
 * what was deleted, what was skipped due to hold, and when.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "disposition_runs")
public class DispositionRun {

    @Id
    private String runId;

    @Indexed(name = "run_startedAt_idx")
    private Instant startedAt;

    private Instant finishedAt;

    private int scanned;
    private int deleted;
    private int skippedOnHold;
    private int failed;
    private int s3ObjectsPurged;
    private int searchPurgePublished;

    /**
     * What started the run: {@code SCHEDULED} for the timer, {@code MANUAL}
     * for an operator calling the API. Recorded because a manual run is a
     * deliberate act by a person and the audit trail should say so.
     */
    private String trigger;

    /** True when the run only reported and deleted nothing. */
    private boolean dryRun;

    /** Set when the run stopped early because maxDeletesPerRun was reached. */
    private boolean deleteCapReached;

    private String lastError;
}
