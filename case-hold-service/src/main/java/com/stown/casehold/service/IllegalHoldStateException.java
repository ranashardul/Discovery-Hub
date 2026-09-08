package com.stown.casehold.service;

/**
 * Raised when a hold operation conflicts with the current state of a case or
 * hold, for example placing a hold on an archived case.
 */
public class IllegalHoldStateException extends RuntimeException {

    public IllegalHoldStateException(String message) {
        super(message);
    }
}
