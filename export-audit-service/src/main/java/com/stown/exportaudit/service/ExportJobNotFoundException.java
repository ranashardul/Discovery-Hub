package com.stown.exportaudit.service;

/**
 * Thrown when an export job cannot be found by ID. Maps to {@code 404 Not
 * Found} by the API exception handler.
 */
public class ExportJobNotFoundException extends RuntimeException {

    public ExportJobNotFoundException(String exportId) {
        super("No export job found for exportId " + exportId);
    }
}
