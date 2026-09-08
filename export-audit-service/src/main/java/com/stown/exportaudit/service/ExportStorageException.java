package com.stown.exportaudit.service;

/**
 * Thrown when an export package cannot be read from or written to S3. Maps to
 * a {@code 502 Bad Gateway} by the API exception handler because it usually
 * indicates the object store is unavailable.
 */
public class ExportStorageException extends RuntimeException {

    public ExportStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
