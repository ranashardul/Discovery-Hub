package com.stown.casehold.service;

public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String caseId) {
        super("No case found for caseId " + caseId);
    }
}
