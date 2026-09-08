package com.stown.search.service;

public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String messageId) {
        super("Message not found in MongoDB messageId=" + messageId);
    }
}
