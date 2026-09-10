package com.stown.ingestion.api;

import com.stown.ingestion.domain.DispositionRun;

import java.time.Instant;

/**
 * One disposition run: what was deleted, what was skipped because of a hold,
 * and when (PRD FR-5.3).
 */
public record DispositionRunResponse(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        /** {@code SCHEDULED} or {@code MANUAL}. */
        String trigger,
        int scanned,
        int deleted,
        int skippedOnHold,
        int failed,
        int s3ObjectsPurged,
        int searchPurgePublished,
        boolean dryRun,
        boolean deleteCapReached,
        String lastError
) {

    public static DispositionRunResponse from(DispositionRun run) {
        return new DispositionRunResponse(
                run.getRunId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                // Runs recorded before the trigger was tracked are scheduler runs:
                // a manual trigger did not exist when they were written.
                run.getTrigger() == null ? "SCHEDULED" : run.getTrigger(),
                run.getScanned(),
                run.getDeleted(),
                run.getSkippedOnHold(),
                run.getFailed(),
                run.getS3ObjectsPurged(),
                run.getSearchPurgePublished(),
                run.isDryRun(),
                run.isDeleteCapReached(),
                run.getLastError()
        );
    }
}
