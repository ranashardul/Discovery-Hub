package com.stown.casehold.service;

public class SavedSearchNotFoundException extends RuntimeException {

    public SavedSearchNotFoundException(String savedSearchId) {
        super("No saved search found for id " + savedSearchId);
    }
}
