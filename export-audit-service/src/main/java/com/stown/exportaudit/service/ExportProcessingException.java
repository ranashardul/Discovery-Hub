package com.stown.exportaudit.service;

/**
 * Thrown when an export package cannot be assembled (for example because the
 * evidence provider or package builder failed). Maps to a {@code 500 Internal
 * Server Error} by the API exception handler.
 */
public class ExportProcessingException extends RuntimeException {

    public ExportProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
