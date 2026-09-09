package com.stown.ingestion.service;

import lombok.Getter;

import java.util.List;

/**
 * Raised when deletion is attempted on a message under legal hold.
 *
 * <p>Surfaces as HTTP 409 and is the demonstrable proof required by PRD
 * FR-4.6: held messages cannot be deleted by any part of the system.
 */
@Getter
public class HeldMessageDeletionException extends RuntimeException {

    private final String messageId;
    private final int holdCount;
    private final List<String> holdIds;

    public HeldMessageDeletionException(String messageId, int holdCount, List<String> holdIds) {
        super("Message %s is under legal hold (holdCount=%d); deletion refused"
                .formatted(messageId, holdCount));
        this.messageId = messageId;
        this.holdCount = holdCount;
        this.holdIds = holdIds == null ? List.of() : List.copyOf(holdIds);
    }
}
