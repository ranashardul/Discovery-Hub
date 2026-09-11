package com.stown.casehold.service;

/**
 * Raised when a hold operation conflicts with the current state of a case or
 * hold, for example releasing a hold that is already released.
 */
public class IllegalHoldStateException extends RuntimeException {

    public IllegalHoldStateException(String message) {
        super(message);
    }
}
