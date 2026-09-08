package com.stown.ingestion.service;

public class InvalidAttachmentException extends RuntimeException {

    public InvalidAttachmentException(String message) {
        super(message);
    }
}
