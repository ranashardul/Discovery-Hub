package com.stown.ingestion.api;

public class IngestionRequestNotFoundException extends RuntimeException {

    public IngestionRequestNotFoundException(String message) {
        super(message);
    }
}
