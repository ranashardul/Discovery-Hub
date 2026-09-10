package com.stown.casehold.service;

/**
 * Raised when a case already has a saved search under the requested name.
 *
 * <p>Surfaces as HTTP 409. Names are how a reviewer refers to a scope during a
 * matter, so silently accepting a duplicate would make two different filter
 * sets indistinguishable in the UI.
 */
public class SavedSearchNameTakenException extends RuntimeException {

    public SavedSearchNameTakenException(String name) {
        super("This case already has a saved search named \"" + name + "\"");
    }
}
