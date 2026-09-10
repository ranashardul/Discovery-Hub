package com.stown.ingestion.service;

/**
 * Raised when a disposition pass is requested while one is already running.
 *
 * <p>Surfaces as HTTP 409. Two concurrent passes would select the same expired
 * candidates and both attempt to delete them, so the second would record
 * failures for work the first had already completed and the run summary would
 * misreport what happened.
 */
public class DispositionInProgressException extends RuntimeException {

    public DispositionInProgressException() {
        super("A disposition run is already in progress; wait for it to finish");
    }
}
