package com.stown.ingestion.service;

public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String messageId) {
        super("No message found for id " + messageId);
    }
}
