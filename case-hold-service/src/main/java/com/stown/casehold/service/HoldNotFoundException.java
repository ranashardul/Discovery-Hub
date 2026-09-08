package com.stown.casehold.service;

public class HoldNotFoundException extends RuntimeException {

    public HoldNotFoundException(String holdId) {
        super("No hold found for holdId " + holdId);
    }
}
